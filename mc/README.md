# Original Fabric passthrough

This folder retains the original Minecraft side from [Rehan's Minecraft–GTA V passthrough example](https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough).

For **Minecraft 1.20.1 and Cracker's Wither Storm Mod**, use [`../mc-forge/`](../mc-forge/README.md) and the [main setup instructions](../README.md). This Fabric implementation is a separate build and does not contain the Forge storm bridge.

## Build configuration

The supplied configuration targets Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, and Java 25. Versions are in `gradle.properties`. A Gradle wrapper is included.

With `JAVA_HOME` pointing to JDK 25, run from this folder:

```powershell
.\gradlew.bat build
```

The result is `build/libs/passthrough-0.1.0.jar`. Install it and the matching Fabric API in a dedicated Fabric instance. Do not install the Forge bridge and this Fabric build together.

The native host plugin and effect are in [`../gta/`](../gta/README.md). Attribution and the preserved upstream license are in the [repository notices](../THIRD_PARTY_NOTICES.md) and [LICENSE](../LICENSE).
