# Attribution and third-party components

## Original passthrough project

This is a modified build of the [Minecraft–GTA V passthrough example](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough) in [rehan-remade/universal-modder](https://github.com/rehan-remade/universal-modder).

The original project supplies the Fabric implementation, GTA script, compositor, shader, and the foundations for the Forge adaptation. The adaptation adds a Minecraft 1.20.1 Forge implementation and integration with Cracker's Wither Storm Mod.

The [upstream MIT license](https://github.com/rehan-remade/universal-modder/blob/main/LICENSE) is preserved in [LICENSE](LICENSE), including its copyright notice: Copyright (c) 2026 Rehan and universal-modder contributors. Existing Java package names and original author metadata are retained for attribution and compatibility.

## Included build tooling

The Gradle wrapper scripts and bootstrap JARs in `mc/` and `mc-forge/` are Gradle build tooling. They are licensed separately under Apache License 2.0. The license extracted from the supplied wrapper JAR is included at [third-party-licenses/gradle-wrapper-LICENSE.txt](third-party-licenses/gradle-wrapper-LICENSE.txt).

## External dependencies

These components are obtained separately or resolved by the build tools; their licenses are not replaced by this repository's MIT license:

- [Cracker's Wither Storm Mod](https://github.com/nonamecrackers2/crackers-wither-storm-mod), by nonamecrackers2. Download the Minecraft 1.20.1 / 4.2.1 JAR from its [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/crackers-wither-storm-mod). The mod's code, models, textures, sounds, and JAR are not bundled here.
- [ScriptHookV and the ASI loader](https://www.dev-c.com/gtav/scripthookv/), by Alexander Blade. The SDK and runtime are fetched separately.
- [ReShade](https://reshade.me/) and [ReShade shader include files](https://github.com/crosire/reshade-shaders), by crosire and contributors. The API headers, runtime, and shared include files are fetched separately. `gta/shaders/MCPassthrough.fx` is the passthrough project's own effect.
- [Minecraft Forge](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html), [SpongePowered Mixin](https://github.com/SpongePowered/Mixin), and the Java/JNA/LWJGL dependencies supplied through Minecraft's development/runtime environment.
- [Fabric](https://fabricmc.net/) and [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket), used by the original `mc/` implementation. Its Gradle build bundles Java-WebSocket in the resulting mod, as declared in `mc/build.gradle`.
- GTA native names and hashes are credited to [alloc8or's NativeDB](https://github.com/alloc8or/gta5-nativedb-data) and its contributors, as in the original project.

Minecraft and GTA V game binaries and assets are not included. This is an independent fan project.
