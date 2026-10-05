[**Support Discord Server**](https://discord.gg/y5kTgtQtgX)

# Hyinit
A restriction-free Mixin bootstrapper for HytaleServer.
Backwards compatible with [Hyxin](https://www.curseforge.com/hytale/mods/hyxin).

## User Guide
Hyinit is NOT a standard early plugin, so do not place it in the earlyplugins folder.
Instead, use `Hyinit-X.X.X.jar` instead of your standard `HytaleServer.jar` to launch the server.
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

Hyinit finds the real server JAR automatically in the working directory or next to itself. For launchers that require `HytaleServer.jar`, use that filename for Hyinit and keep the real server beside it under another `.jar` filename; no additional bootstrap arguments are needed.
To point at a specific server jar, pass `--server-jar=<path>`.
To load early plugins from extra directories, pass `--early-plugins=<dir1,dir2>`.
Hyinit scans `earlyplugins` in the working directory and next to its JAR, plus these extra directories, for Mixins. Hytale already scans the working-directory folder, so Hyinit forwards only the other resolved directories for native early-plugin discovery, avoiding duplicate scans of the same directory.
For eligible server classes, native transformations run before Mixins using the same classloader.

## Developer Guide
### Dependencies
Hyinit currently does not have its own API, so you should depend directly on
[FabricMC's Mixin fork](https://github.com/FabricMC/Mixin) and [MixinExtras](github.com/LlamaLad7/MixinExtras):
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

## Note
The MixinService and the Mixin class loader implementations are partially based on
[fabric-loader's](https://github.com/FabricMC/fabric-loader) Knot implementation.
