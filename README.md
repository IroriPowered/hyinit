# Hyinit
Restriction-free Mixin bootstrapper for Hytale (backwards compatibility with [Hyxin](https://www.curseforge.com/hytale/mods/hyxin) included)

## Proof of Concept
This bootstrapper is currently in early development!

## Usage (Temp)
### Starting
Run Hyinit JAR with the standard HytaleServer launch arguments.
Hyinit will automatically locate `HytaleServer.jar` and boot with Mixins enabled.
```shell
java -jar Hyinit-1.0.jar [the rest of HytaleServer launch args]
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