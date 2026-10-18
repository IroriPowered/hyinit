[**Support Discord Server**](https://discord.gg/y5kTgtQtgX)

# Hyinit
A restriction-free Mixin bootstrapper for HytaleServer.
Backwards compatible with [Hyxin](https://www.curseforge.com/hytale/mods/hyxin).

## User Guide
Hyinit is NOT a standard early plugin, so do not place it in the earlyplugins folder.
Launch with `Hyinit-X.X.X.jar` in place of `HytaleServer.jar`.
When launching, **make sure that both Hyinit and HytaleServer JARs are in the same directory.**

For example, if you are currently using a command like this to start the server normally:
```shell
java -Xms10G -Xmx10G -jar HytaleServer.jar --assets=../Assets.zip
````
You may start the server through Hyinit using the following command instead:
```shell
java -Xms10G -Xmx10G -jar Hyinit-X.X.X.jar --assets=../Assets.zip
````
Now, you can install mod JARs that depend on Hyinit Mixin environment in the earlyplugins folder.

Hyinit finds the server JAR in the working directory or beside itself. If your launcher requires `HytaleServer.jar`, rename Hyinit to that and give the real server JAR another name.
To point at a specific server jar, pass `--server-jar=<path>`.
To load early plugins from extra directories, pass `--early-plugins=<dir1,dir2>`.
Hyinit scans `earlyplugins` in both locations and any extra directories. It also passes extra directories to Hytale without duplicating its default scan.
For eligible server classes, native transformations run before Mixins using the same classloader.

Legacy version compatibility is off by default. Add `-Dhyinit.compatibility.legacyVersions=true` before `-jar` to enable it.

## Developer Guide
### Dependencies
Hyinit currently does not have its own API, so you should depend directly on
[FabricMC's Mixin fork](https://github.com/FabricMC/Mixin) and [MixinExtras](https://github.com/LlamaLad7/MixinExtras):
```kotlin
dependencies {
    // Mixin
    compileOnly("net.fabricmc:sponge-mixin:${MIXIN_VERSION}")
    // MixinExtras
    compileOnly("io.github.llamalad7.mixinextras:mixinextras-fabric:${MIXINEXTRAS_VERSION}")
}
```

### Adding Mixins
Put JARs with your Mixins in the `earlyplugins` folder.
In `manifest.json`, add the following entry:
```json
{
    "Mixins": [
        "your_mixin_config.mixins.json"
    ]
}
```

### Hyxin Compatibility
`manifest.json` with the following entry will also be read for compatibility with Hyxin:
```json
{
    "Hyxin": {
        "Configs": [
            "your_mixin_config.mixins.json"
        ]
    }
}
```

### Combined JAR Shared Packages

Combined JARs can share selected packages with the early loader:

```json
{
    "HyinitSharedPackages": [
        "cc.irori.refixes.early"
    ]
}
```

Add this beside `Main` and `Mixins`. Sharing applies only within the same JAR. Keep the main class and runtime services outside these packages. Overrides from separate mod JARs stay local.

## Note
The MixinService and the Mixin class loader implementations are partially based on
[fabric-loader's](https://github.com/FabricMC/fabric-loader) Knot implementation.
