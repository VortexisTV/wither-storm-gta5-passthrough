# Forge 1.20.1 bridge with Cracker's Wither Storm Mod

This is the Minecraft 1.20.1 adaptation of [Rehan's Minecraft–GTA V passthrough](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough). It runs on Forge and connects the real Cracker's Wither Storm Mod simulation to GTA's NPCs and vehicles. The original Fabric implementation is retained in [`../mc/`](../mc/README.md).

Follow the [main README](../README.md) for the complete Minecraft and GTA installation.

## Build

The build uses **JDK 17**, Minecraft **1.20.1**, Forge **47.4.10**, and Wither Storm Mod **4.2.1**. The Gradle 8.8 wrapper is included.

Create a `libs/` folder here and place `witherstormmod-1.20.1-4.2.1-all.jar` in it. Download the mod separately from [CurseForge](https://www.curseforge.com/minecraft/mc-mods/crackers-wither-storm-mod). With `JAVA_HOME` set to JDK 17, run:

```powershell
.\gradlew.bat build
```

Output: `build/libs/passthrough-forge-0.1.0.jar`. Install this JAR and the storm mod JAR into a dedicated Forge 1.20.1 instance. The storm dependency is compiled against with `compileOnly`; it is not bundled into the bridge.

To use a different dependency location:

```powershell
.\gradlew.bat build "-Pwitherstorm_dir=D:/Minecraft/StormInstance/mods"
```

For an existing Prism instance, the optional helper builds against that instance's installed storm JAR and copies the bridge there:

```powershell
.\tools\redeploy.ps1 -Instance "1.20.1" -NoLaunch
```

Close Minecraft before using it. Use `-PrismRoot` for a portable/custom Prism data directory, and `-PrismExe` for a custom launcher executable. Omitting `-NoLaunch` starts the instance after installation.

## Storm behavior

- Each GTA person or vehicle near the player or storm receives an invisible Minecraft proxy. The storm targets these through its own logic. Grabbed proxies move their GTA counterparts, and consumed proxies remove those entities from GTA.
- GTA ground is represented by invisible `passthrough:ground` blocks. Torn clusters appear as Minecraft rubble and cause GTA dust and forces. GTA's road, terrain, and building geometry remain intact.
- Skulls and fireballs are traced through GTA's world and mirrored as GTA explosions.
- The bridge transfers weather, lightning, camera shake, beam lighting, fleeing behavior, and vehicle impacts.
- Police damage can make the storm react to its attacker; police cannot kill it through this bridge.

Storm controls are F9 to summon, Shift+F9 for phase 4, F10 to evolve, F11 to remove, and Shift+F11 for its death sequence. F7 toggles the bridge and F8 re-levels the ground.

Optional Minecraft JVM arguments tune proxy mass:

```text
-Dpassthrough.pedMass=40
-Dpassthrough.vehicleMass=250
```

These are the current defaults. A ground block contributes one unit of mass.

## Implementation

`storm/StormBridge.java` connects proxy entities and ground events to the installed storm mod. Storm mixins are enabled only when that mod is present. The actual storm's simulation and assets come from the external mod.

Minecraft 1.20.1 exports its OpenGL depth with frame flags `2`; the shared GTA shader also supports the Fabric implementation's depth convention. Windows shared memory uses JNA, and the bridge supplies a small built-in WebSocket server on `127.0.0.1:25599`. Keep this default when using the supplied GTA plugin.

The bridge creates/opens a creative void world named `passthrough` and changes settings such as background focus behavior, clouds, bobbing, and frame limit. Use a dedicated instance. Keep Minecraft's window open while GTA has focus.

## Testing

With the dedicated Forge instance running, use Windows Python for the host stand-in:

```powershell
python tools\fakehost.py --storm 4 --seconds 60
```

It uses Python's standard library, sends a camera, ground, and fake NPC/vehicle proxies, and saves exported frames to `fakehost_out/`. Run it while GTA's bridge is disconnected, because it acts as the host itself.

The native host harnesses are documented in [`../gta/README.md`](../gta/README.md). Offline checks cannot reproduce GTA engine faults. The actual Forge storm integration has been used in GTA V Legacy for NPC and vehicle grabbing; compatibility and performance still depend on the local game/mod setup.

The images in `docs/` are Minecraft-side development captures, rather than GTA gameplay screenshots.

The preserved [MIT license](../LICENSE) and [upstream attribution](../THIRD_PARTY_NOTICES.md) apply to the passthrough source. Cracker's Wither Storm Mod is a separate dependency with its own terms.
