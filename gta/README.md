# GTA V host plugin and ReShade compositor

The GTA side is based on [Rehan's passthrough example](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough). This build adds the Forge bridge's Wither Storm events and NPC/vehicle interactions. See the [main README](../README.md) for the complete setup.

## Build

Install MSVC x64 and the Windows SDK through Visual Studio / Build Tools. `build.bat` uses `vswhere` to find the newest installation with C++ tools; set `VCVARS` to another `vcvars64.bat` if needed.

Run `bash gta/fetch_deps.sh` from the repository root in WSL. Its output is intentionally ignored by Git. It prepares:

| Directory / file | Contents |
| --- | --- |
| `third_party/shv/` | ScriptHookV SDK `main.h`, `nativeCaller.h`, `types.h`, and `ScriptHookV.lib` |
| `third_party/reshade/` | ReShade 6.8.0 add-on API headers |
| `third_party/ReShade.fxh`, `ReShadeUI.fxh` | Shader include files |
| `third_party/runtime/` | `ScriptHookV.dll`, `dinput8.dll`, and full add-on `ReShade64.dll` |

If automatic fetching fails, download the [ScriptHookV SDK/runtime](https://www.dev-c.com/gtav/scripthookv/) and [full add-on ReShade](https://reshade.me/) from their official sites. ReShade headers are from [`crosire/reshade` at v6.8.0](https://github.com/crosire/reshade/tree/v6.8.0/include); shader includes are from [`crosire/reshade-shaders`, slim branch](https://github.com/crosire/reshade-shaders/tree/slim/Shaders). Match the directory layout above.

Then, from the repository root in PowerShell:

```powershell
.\gta\build.bat
```

Output: `gta/build/MCPassthrough.asi`.

The optional WSL `build.sh` uses the current checkout on a Windows drive by default. If the source is in a Linux filesystem, set `PASSTHROUGH_WIN_DIR` to a Windows working directory; it copies the GTA source there and invokes MSVC. `bash gta/build.sh tests` also builds the graphics/WebSocket harnesses.

## Install and restore

The WSL `install.sh` performs the complete runtime installation. It accepts `GTA_DIR`, `RUNTIME`, and `BUILD` overrides and can discover a Steam Legacy installation. Without a `BUILD` override, it uses this checkout's `build/` output or the configured Windows mirror.

ReShade is loaded through the ASI loader as `ReShade64.asi` for this project's GTA setup. The installer also configures the `MCPassthrough` effect and shader search paths. `install.sh --remove` removes the files listed in that script; review the list before using it on an installation shared with other mods.

The PowerShell installer only updates the bridge and its shader in an already-configured game:

```powershell
.\gta\install.ps1 -Gta "D:\Games\Grand Theft Auto V"
.\gta\install.ps1 -Gta "D:\Games\Grand Theft Auto V" -Restore ".\gta\backup-YYYY-MM-DD"
```

It also reads `GTA_DIR` from the environment if `-Gta` is omitted, and refuses to update while GTA is running.

## Source and offline harnesses

- `src/script.cpp`: GTA camera/input/world synchronization and storm responses.
- `src/compositor.cpp`: shared-frame upload and ReShade add-on registration.
- `src/natives.h`: native calls by hash.
- `src/ws.cpp`: local WebSocket client.
- `shaders/MCPassthrough.fx`: depth compositing, camera reprojection, relighting, and HUD/hand overlay.
- `tests/fakegta.cpp`: a D3D11 host stand-in for rendering checks.
- `tests/ws_test.cpp`: WebSocket bridge checks against a running Minecraft instance.
- `tests/fakeshv.cpp`, `shvhost.cpp`: a small ScriptHookV stand-in for exercising the plugin's event handling outside GTA.

Build the graphics/WebSocket harnesses with `tests/build_fakegta.bat`, or the ScriptHookV harness with `tests/build_fakeshv.bat` after building the plugin. These harnesses simulate a small world; they do not reproduce GTA's engine, streaming, or native failure behavior.

The storm uses GTA explosions and forces, but this build has no persistent street spots, map cutaways, or building-model hiding.
