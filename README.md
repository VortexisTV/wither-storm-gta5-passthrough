# Wither Storm × GTA V Passthrough

Run **Minecraft Java 1.20.1 with Cracker's Wither Storm Mod** alongside **GTA V Legacy**, and bring the actual Minecraft storm into Los Santos. Minecraft renders and simulates the storm; the bridge draws it into GTA's view and makes GTA's people and vehicles react to what it does.

This is a modified build of [Rehan's Minecraft–GTA V passthrough example](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough), from [universal-modder](https://github.com/rehan-remade/universal-modder). This adaptation adds **Forge 1.20.1, Cracker's Wither Storm Mod integration, and GTA NPC/vehicle interaction with the storm**. The original Fabric code is also included in `mc/`.

## Features

- The real Wither Storm runs in Minecraft, including its growth, heads, tractor beams, targeting, and consumption.
- Nearby GTA NPCs and cars have invisible Minecraft proxies. When the storm grabs a proxy, its GTA counterpart is pulled into the air; when consumed, the GTA entity is removed.
- Minecraft fireballs and skulls produce impacts and explosions in GTA.
- Storm weather, lightning, camera shake, fleeing pedestrians, beam lighting, and vehicle impacts affect GTA.
- Minecraft's color and depth are composited with GTA's depth, so the storm can appear behind GTA scenery.
- The underlying passthrough also includes block placement, explosions, weapons, mobs, and Nether interactions.

**GTA buildings, roads, and terrain remain intact.** Minecraft ground proxies can become flying rubble and trigger dust and forces in GTA. This build does not create street spots, persistent craters, terrain cutaways, or remove building models.

## Requirements

| Component | Version / requirement |
| --- | --- |
| OS | Windows; both games run on the same computer |
| GTA | GTA V **Legacy**, Story Mode (`GTA5.exe`) |
| Minecraft | Java Edition **1.20.1**, in a dedicated launcher instance |
| Forge | **47.4.10**, matching the current build configuration |
| Wither Storm | **4.2.1 for Minecraft 1.20.1**: `witherstormmod-1.20.1-4.2.1-all.jar` |
| Java | **JDK 17** for the Forge build and launcher instance |
| GTA scripting | [ScriptHookV and its ASI loader](https://www.dev-c.com/gtav/scripthookv/), compatible with your installed GTA build |
| Compositor | [ReShade 6.8.0 with full add-on support](https://reshade.me/) |
| C++ build | Visual Studio / Build Tools with Desktop development with C++, MSVC x64, and Windows SDK |
| Dependency/install scripts | WSL with Bash, `curl`, and `unzip`; `rsync` if using a separate Windows build mirror |

Use Story Mode with BattlEye disabled. ScriptHookV does not support [GTA Online](https://www.dev-c.com/gtav/scripthookv/). GTA V Enhanced is not the target of this build.

Download [Cracker's Wither Storm Mod](https://www.curseforge.com/minecraft/mc-mods/crackers-wither-storm-mod) separately. Game files, the storm mod, ScriptHookV, and ReShade downloads are not included in this repository.

## Build and install

Keep the checkout on a Windows drive, such as `C:\Projects\wither-storm-gta-passthrough`, so Windows Java and MSVC can build it. The commands below run from the repository root unless stated otherwise.

### 1. Build the Forge bridge

Set `JAVA_HOME` to your JDK 17 directory. Create `mc-forge/libs/` and put the downloaded storm mod JAR there, then run in PowerShell:

```powershell
cd mc-forge
.\gradlew.bat build
cd ..
```

The result is `mc-forge/build/libs/passthrough-forge-0.1.0.jar`. The storm mod is a compile-time dependency and is not bundled into this JAR.

To use a storm JAR already installed elsewhere, override its directory:

```powershell
cd mc-forge
.\gradlew.bat build "-Pwitherstorm_dir=D:/Minecraft/StormInstance/mods"
cd ..
```

Create a dedicated Minecraft 1.20.1 instance with Forge 47.4.10. Put **both** the bridge JAR and the storm mod JAR in that instance's `mods` folder. Use Java 17 for that instance. A dedicated instance matters because the bridge creates/opens a creative void world called `passthrough` and adjusts Minecraft's graphics and focus settings.

### 2. Fetch GTA dependencies and build the plugin

In WSL, change to the repository on the Windows drive, then run:

```bash
bash gta/fetch_deps.sh
```

This fetches the ScriptHookV SDK, ReShade headers, shader includes, and runtime files into the ignored `gta/third_party/` folder. If a download is unavailable, obtain the matching files from the official sites; see [GTA build details](gta/README.md).

In PowerShell:

```powershell
.\gta\build.bat
```

The result is `gta/build/MCPassthrough.asi`. It contains both the ScriptHookV script and the ReShade add-on.

### 3. Install into GTA V Legacy

Close GTA before installing. For a complete first installation, run from WSL, replacing the example with your own game directory:

```bash
GTA_DIR="/mnt/d/Games/Grand Theft Auto V" bash gta/install.sh
```

The installer adds the ASI loader, ScriptHookV, the bridge, and ReShade loaded as `ReShade64.asi`, plus the shader and configuration. It checks for conflicting loader, ReShade, and launch-argument files before replacing them. Review the target directory and any existing mod setup first.

For later updates to an installation that already has ScriptHookV, the ASI loader, and ReShade configured:

```powershell
.\gta\install.ps1 -Gta "D:\Games\Grand Theft Auto V"
```

This PowerShell helper updates only `MCPassthrough.asi` and `MCPassthrough.fx` and keeps a backup. It also accepts `-Restore` with a backup directory.

### 4. Run

1. Start the dedicated Forge instance. Let it load the `passthrough` world.
2. Leave Minecraft running in the background with its window open.
3. Start GTA V Legacy with BattlEye disabled and select **Story Mode**.
4. The plugin connects to Minecraft on `127.0.0.1:25599` and composites its view into GTA.
5. Press **Shift+F9** for an already-grown storm, or **F9** to start its normal growth sequence.

The player stays in creative mode. GTA movement and camera control drive the Minecraft player and camera.

## Controls in GTA

| Key | Action |
| --- | --- |
| F7 | Toggle the passthrough |
| F8 | Re-level Minecraft's ground at the player's position |
| F9 | Summon a Wither Storm ahead of the player |
| Shift+F9 | Summon a grown storm at phase 4 |
| F10 | Advance the storm one phase |
| F11 | Remove the storm |
| Shift+F11 | Start its death sequence |
| Left / right mouse | Minecraft attack / use |
| Mouse wheel / 1–9 | Minecraft hotbar selection |
| Tab | Cycle experimental GTA gun modes, then return to Minecraft input |

## Source layout

| Folder | Contents |
| --- | --- |
| [`mc-forge/`](mc-forge/README.md) | Forge 1.20.1 bridge, storm integration, mixins, Gradle wrapper, and test helper |
| [`gta/`](gta/README.md) | C++ ScriptHookV plugin, ReShade compositor/effect, build/install scripts, and offline harnesses |
| [`mc/`](mc/README.md) | Original Fabric 26.3 implementation, retained for reference and separate builds |
| `mc-forge/docs/` | Minecraft-side development screenshots |
| [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) | Upstream attribution and external dependency information |

## How the bridge works

GTA sends camera, player, input, and sampled ground data to Minecraft over a local WebSocket. Minecraft exports world color, world depth, and a separate HUD/hand layer through the Windows shared-memory mapping `Local\MCPassthroughFrame`. The ReShade add-on uploads these frames, and `MCPassthrough.fx` combines them with GTA's view.

The Forge storm bridge reports tractor beams, grabbed entities, consumption, and torn ground. GTA applies the corresponding movement, removals, forces, explosions, and weather. One GTA meter corresponds to one Minecraft block.

## Testing and limitations

The Forge adaptation has been used in GTA V Legacy with the storm visibly present and grabbing NPCs and vehicles. This is an experimental integration; rendering, performance, and native behavior depend on the game build and installed mods.

- Road, terrain, and building geometry are not destructible in this release.
- A large storm and two running games can require substantial CPU/GPU resources.
- ReShade needs a valid GTA depth buffer. Check the effect, full add-on support, and depth settings if occlusion or the overlay is missing.
- Only one bridge instance should use the default local port and shared-memory mapping at a time.
- The offline harnesses check bridge behavior, but cannot prove GTA native stability. See the [Forge testing instructions](mc-forge/README.md#testing).

## Credits and license

The original passthrough implementation is by **Rehan and the universal-modder contributors**. This repository adapts [their example](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough) to Forge 1.20.1 and adds the Wither Storm bridge.

Cracker's Wither Storm Mod is by **nonamecrackers2**; its simulation and assets come from the separately installed mod. 
ScriptHookV and its ASI loader by Alexander Blade.
ReShade and its add-on API by crosire, with ReShade.fxh from crosire/reshade-shaders.

The project also uses Forge, Mixin, Fabric in the original implementation, and native names/hashes from [alloc8or's NativeDB](https://github.com/alloc8or/gta5-nativedb-data).

The passthrough source is distributed under the [MIT license](LICENSE), preserving the [upstream license and copyright notice](https://github.com/rehan-remade/universal-modder/blob/main/LICENSE). Third-party components retain their own licenses. 

Written with Claude Code + Codex.
Minecraft belongs to Mojang Studios and Microsoft, and GTA V to Rockstar Games and Take-Two. This is a fan project.