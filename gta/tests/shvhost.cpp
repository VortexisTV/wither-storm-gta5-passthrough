// Runs the real MCPassthrough.asi against the stand-in ScriptHookV.dll (fakeshv.cpp), with Minecraft running:
//
//   shvhost.exe [seconds] [key@time ...]      e.g.  shvhost.exe 70 F10@40 F11@62
//
// Keys: F7 F8 F9 F10 F11 (the plugin's own). Both DLLs are looked for next to this exe.
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

extern "C" __declspec(dllimport) void fake_run(double seconds, const double *keyTimes, const int *keys, int keyCount);

int main(int argc, char **argv)
{
	const double seconds = argc > 1 ? std::atof(argv[1]) : 60.0;
	std::vector<double> times;
	std::vector<int> keys;
	for (int i = 2; i < argc; ++i)
	{
		const char *at = std::strchr(argv[i], '@');
		if (at == nullptr || (argv[i][0] != 'F' && argv[i][0] != 'f'))
			continue;
		const int f = std::atoi(argv[i] + 1);
		if (f < 1 || f > 12)
			continue;
		keys.push_back(VK_F1 + f - 1);
		times.push_back(std::atof(at + 1));
	}
	if (LoadLibraryA("MCPassthrough.asi") == nullptr)
	{
		std::printf("couldn't load MCPassthrough.asi next to this exe (error %lu)\n", GetLastError());
		return 1;
	}
	std::puts("MCPassthrough.asi loaded; running its script");
	std::fflush(stdout);
	fake_run(seconds, times.data(), keys.data(), int(keys.size()));
	return 0;
}
