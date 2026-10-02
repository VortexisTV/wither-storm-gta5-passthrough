// A stand-in for ScriptHookV.dll, to run the real MCPassthrough.asi outside GTA: it exports what the plugin
// imports (script registration, the native call interface, the entity pools), keeps a tiny world of a player, some
// people and some cars, and answers the natives the script's logic depends on. Everything else returns zero.
//
// With Minecraft running (and its mod's link up), shvhost.exe loads the .asi next to this DLL and lets its script
// run for a while: the script connects, sends its camera, ground and people as it does in GTA, and whatever
// Minecraft answers (storms, what they grab and eat, explosions) goes through the script's own handlers. What the
// script then does to the "world" (velocities, deletions, explosions, weather, notifications) is printed.
//
// It tests the plugin's logic and that it doesn't crash; it says nothing about how GTA's natives really behave.
#include <windows.h>
#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#define EXPORT __declspec(dllexport)

typedef void (*KeyboardHandler)(DWORD, WORD, BYTE, BOOL, BOOL, BOOL, BOOL);

namespace
{
	struct Thing
	{
		int type = 1; // 1 a person, 2 a vehicle
		float x = 0, y = 0, z = 0, vx = 0, vy = 0, vz = 0;
		int velocitySets = 0;
		float top = 0; // the highest it got
	};
	std::map<int, Thing> g_world;
	const int kPlayer = 1;
	void (*g_script)() = nullptr;
	KeyboardHandler g_keyboard = nullptr;
	UINT64 g_hash = 0;
	std::vector<UINT64> g_args;
	UINT64 g_result[4] = {};
	LARGE_INTEGER g_start = {}, g_freq = {};
	int g_frame = 0;
	double g_last = 0, g_seconds = 60;
	std::map<UINT64, int> g_calls;
	std::vector<std::pair<double, int>> g_keys; // (time, virtual key) to press
	int g_deletedPeds = 0, g_deletedVehicles = 0, g_explosions = 0, g_flashes = 0, g_notes = 0, g_fled = 0, g_ragdolls = 0;

	double now()
	{
		LARGE_INTEGER c;
		QueryPerformanceCounter(&c);
		return double(c.QuadPart - g_start.QuadPart) / double(g_freq.QuadPart);
	}

	float argf(size_t i)
	{
		float f = 0;
		if (i < g_args.size())
			std::memcpy(&f, &g_args[i], sizeof(f));
		return f;
	}

	int argi(size_t i)
	{
		return i < g_args.size() ? int(g_args[i]) : 0;
	}

	template <typename T>
	T *argp(size_t i)
	{
		return i < g_args.size() ? reinterpret_cast<T *>(g_args[i]) : nullptr;
	}

	void ret_vec(float x, float y, float z)
	{
		float out[6] = {x, 0, y, 0, z, 0};
		std::memcpy(g_result, out, sizeof(out));
	}

	void ret_int(int v)
	{
		g_result[0] = UINT64(unsigned(v));
	}

	void ret_float(float v)
	{
		std::memcpy(g_result, &v, sizeof(v));
	}

	void say(const char *format, ...)
	{
		va_list args;
		va_start(args, format);
		std::printf("[%6.1f] ", now());
		std::vprintf(format, args);
		std::printf("\n");
		std::fflush(stdout);
		va_end(args);
	}

	void populate()
	{
		// GTA coordinates (z up). The ground is at 63.6 everywhere; origins sit 1 m (people) / 0.6 m (cars) above it.
		// The player stands at Minecraft (0.5, 64, -6) and looks towards Minecraft +z, like tools/fakehost.py.
		g_world[kPlayer] = {1, 0.5f, 6.0f, 64.6f};
		for (int i = 0; i < 12; ++i)
		{
			const float a = i * 2.39996f, r = 6.0f + float((i * 5) % 22);
			g_world[1000 + i] = {1, r * std::cos(a), -(22.0f + r * std::sin(a) * 0.6f), 64.6f};
		}
		for (int i = 0; i < 4; ++i)
			g_world[2000 + i] = {2, -12.0f + i * 8.0f, -(22.0f + 5.0f * (i % 2)), 64.2f};
	}

	void step(double dt)
	{
		for (auto &[handle, t] : g_world)
		{
			if (handle == kPlayer)
				continue;
			t.x += float(t.vx * dt);
			t.y += float(t.vy * dt);
			t.z += float(t.vz * dt);
			const float rest = t.type == 1 ? 64.6f : 64.2f;
			if (t.z > rest)
				t.vz -= float(9.8 * dt); // what isn't carried falls
			if (t.z <= rest)
			{
				t.z = rest;
				t.vx = t.vy = t.vz = 0;
			}
			t.top = std::max(t.top, t.z);
		}
	}

	void summary()
	{
		std::printf("\n== after %.0f s, %d frames ==\n", now(), g_frame);
		std::printf("deleted: %d people, %d vehicles; explosions %d; lightning %d; notifications %d; told to flee %d; ragdolled %d\n",
			g_deletedPeds, g_deletedVehicles, g_explosions, g_flashes, g_notes, g_fled, g_ragdolls);
		for (const auto &[handle, t] : g_world)
			if (handle != kPlayer)
				std::printf("  %d (%s): at %.1f %.1f %.1f, highest %.1f, velocity set %d times\n", handle, t.type == 1 ? "person" : "vehicle",
					t.x, t.y, t.z, t.top, t.velocitySets);
		std::fflush(stdout);
	}

	void call()
	{
		std::memset(g_result, 0, sizeof(g_result));
		++g_calls[g_hash];
		const Thing &me = g_world[kPlayer];
		switch (g_hash)
		{
		case 0xD80958FC74E988A6: ret_int(kPlayer); break;            // PLAYER_PED_ID
		case 0x3FEF770D40960D5A:                                     // GET_ENTITY_COORDS
		{
			auto it = g_world.find(argi(0));
			if (it != g_world.end())
				ret_vec(it->second.x, it->second.y, it->second.z);
			break;
		}
		case 0x4805D2B1D8CF94A9:                                     // GET_ENTITY_VELOCITY
		{
			auto it = g_world.find(argi(0));
			if (it != g_world.end())
				ret_vec(it->second.vx, it->second.vy, it->second.vz);
			break;
		}
		case 0xA200EB1EE790F448:                                     // GET_FINAL_RENDERED_CAM_COORD
		case 0x14D6F5678D8F1B37: ret_vec(me.x, me.y, me.z + 0.65f); break; // GET_GAMEPLAY_CAM_COORD
		case 0x5B4E4C817FCC2DFB:                                     // GET_FINAL_RENDERED_CAM_ROT
		case 0x837765A25378F0BB: ret_vec(25.0f, 0.0f, 180.0f); break; // GET_GAMEPLAY_CAM_ROT: 25 degrees up, towards -y
		case 0x80EC114669DAEFF4:                                     // GET_FINAL_RENDERED_CAM_FOV
		case 0x65019750A0324133: ret_float(50.0f); break;
		case 0xD0082607100D7193: ret_float(0.15f); break;
		case 0xDFC8CBC606FDB0FC: ret_float(10000.0f); break;
		case 0x8D4D46230B2C353A: ret_int(4); break;                  // GET_FOLLOW_PED_CAM_VIEW_MODE: first person
		case 0xE83D4F9BA2A38914: ret_float(180.0f); break;           // GET_ENTITY_HEADING
		case 0xC906A7DAB05C8D2B:                                     // GET_GROUND_Z_FOR_3D_COORD
			if (float *out = argp<float>(3))
				*out = 63.6f;
			ret_int(1);
			break;
		case 0xFC8202EFC642E6F2: ret_int(g_frame); break;            // GET_FRAME_COUNT
		case 0x9CD27B0045628463: ret_int(int(now() * 1000.0)); break; // GET_GAME_TIMER
		case 0x7239B21A38F536BA: ret_int(g_world.count(argi(0)) ? 1 : 0); break; // DOES_ENTITY_EXIST
		case 0x8ACD366038D14505:                                     // GET_ENTITY_TYPE
		{
			auto it = g_world.find(argi(0));
			ret_int(it != g_world.end() ? it->second.type : 0);
			break;
		}
		case 0xFF059E1E4C01E63C: ret_int(4); break;                  // GET_PED_TYPE: a civilian
		case 0xEEF059FAD016D209: ret_int(200); break;                // GET_ENTITY_HEALTH
		case 0x1C99BB7B6E96D16F:                                     // SET_ENTITY_VELOCITY
		{
			auto it = g_world.find(argi(0));
			if (it != g_world.end())
			{
				it->second.vx = argf(1);
				it->second.vy = argf(2);
				it->second.vz = argf(3);
				if (++it->second.velocitySets == 1)
					say("%d is being carried off (first velocity %.1f %.1f %.1f)", argi(0), argf(1), argf(2), argf(3));
			}
			break;
		}
		case 0x239A3351AC1DA385:                                     // SET_ENTITY_COORDS_NO_OFFSET
		{
			auto it = g_world.find(argi(0));
			if (it != g_world.end() && argi(0) != kPlayer)
			{
				it->second.x = argf(1);
				it->second.y = argf(2);
				it->second.z = argf(3);
			}
			break;
		}
		case 0x9614299DCB53E54B:                                     // DELETE_PED
		case 0xAE3CBE5BF394C9C9:                                     // DELETE_ENTITY
			if (int *handle = argp<int>(0))
			{
				auto it = g_world.find(*handle);
				if (it != g_world.end())
				{
					say("%s %d is gone (eaten at %.1f %.1f %.1f)", it->second.type == 1 ? "person" : "vehicle", *handle, it->second.x, it->second.y, it->second.z);
					++(it->second.type == 1 ? g_deletedPeds : g_deletedVehicles);
					g_world.erase(it);
				}
				*handle = 0;
			}
			break;
		case 0xE3AD2BDBAEE269AC:                                     // ADD_EXPLOSION
			if (++g_explosions <= 12)
				say("explosion type %d at %.1f %.1f %.1f", argi(3), argf(0), argf(1), argf(2));
			break;
		case 0x6C188BE134E074AA:                                     // the text of a notification
			++g_notes;
			say("notification: %s", argp<const char>(0) ? argp<const char>(0) : "?");
			break;
		case 0xFB5045B7C42B75BF: say("weather -> %s over %.0f s", argp<const char>(0), argf(1)); break;
		case 0xFD55E49555E017CF: say("camera shake %s %.2f", argp<const char>(0), argf(1)); break;
		case 0xF6062E089251C898: ++g_flashes; break;                 // FORCE_LIGHTNING_FLASH
		case 0x94587F17E9C365D5: ++g_fled; break;                    // TASK_SMART_FLEE_COORD
		case 0xAE99FB955581844A: ++g_ragdolls; break;                // SET_PED_TO_RAGDOLL
		default: break;
		}
	}
}

EXPORT void scriptWait(DWORD)
{
	++g_frame;
	Sleep(8);
	const double t = now();
	step(std::min(t - g_last, 0.1));
	g_last = t;
	for (auto &[at, key] : g_keys)
		if (key != 0 && t >= at && g_keyboard != nullptr)
		{
			say("key 0x%X pressed", key);
			g_keyboard(DWORD(key), 1, 0, FALSE, FALSE, FALSE, FALSE);
			key = 0;
		}
	if (t >= g_seconds)
	{
		summary();
		ExitProcess(0);
	}
}

EXPORT void scriptRegister(HMODULE, void (*main)()) { g_script = main; }
EXPORT void scriptRegisterAdditionalThread(HMODULE, void (*)()) {}
EXPORT void scriptUnregister(HMODULE) {}
EXPORT void scriptUnregister(void (*)()) {}
EXPORT void keyboardHandlerRegister(KeyboardHandler handler) { g_keyboard = handler; }
EXPORT void keyboardHandlerUnregister(KeyboardHandler) { g_keyboard = nullptr; }
EXPORT void nativeInit(UINT64 hash)
{
	g_hash = hash;
	g_args.clear();
}
EXPORT void nativePush64(UINT64 value) { g_args.push_back(value); }
EXPORT PUINT64 nativeCall()
{
	call();
	return g_result;
}
EXPORT int worldGetAllPeds(int *out, int size)
{
	int n = 0;
	for (const auto &[handle, t] : g_world)
		if (t.type == 1 && n < size)
			out[n++] = handle;
	return n;
}
EXPORT int worldGetAllVehicles(int *out, int size)
{
	int n = 0;
	for (const auto &[handle, t] : g_world)
		if (t.type == 2 && n < size)
			out[n++] = handle;
	return n;
}

/// shvhost.exe: run the registered script for `seconds`, pressing the given keys at the given times.
extern "C" EXPORT void fake_run(double seconds, const double *keyTimes, const int *keys, int keyCount)
{
	QueryPerformanceFrequency(&g_freq);
	QueryPerformanceCounter(&g_start);
	g_seconds = seconds;
	for (int i = 0; i < keyCount; ++i)
		g_keys.emplace_back(keyTimes[i], keys[i]);
	populate();
	if (g_script == nullptr)
	{
		std::puts("no script registered (did the .asi load?)");
		return;
	}
	g_script(); // never returns: scriptWait ends the process when the time is up
}
