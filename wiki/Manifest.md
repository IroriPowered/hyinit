# manifest.json

[Home](Home.md) · [Launching](Launching.md)

Put `manifest.json` at the root of your plugin JAR. In a standard Gradle project, place it in `src/main/resources/manifest.json`.

## Combined-plugin example

This JAR contains both a native plugin and Mixins:

```json
{
    "Group": "Example",
    "Name": "ExampleMod",
    "Version": "1.0.0",
    "ServerVersion": "*",
    "Main": "com.example.examplemod.nativeplugin.ExamplePlugin",
    "Mixins": ["examplemod.mixins.json"],
    "HyinitMixinCompatibility": "0.17.5",
    "HyinitSharedPackages": ["com.example.examplemod.shared"]
}
```

## Hytale fields

These fields belong to Hytale's plugin manifest, not Hyinit:

- `Group` and `Name`: strings that form the plugin ID `Group:Name`. The example's ID is `Example:ExampleMod`.
- `Version`: the plugin's semantic version.
- `ServerVersion`: a server-version range supported by Hytale's native loader. `*` allows any version.
- `Main`: the fully qualified native plugin entry point. Omit it for an early-only JAR with no native plugin.

Hyinit reads the ID, `Main` and `DisabledByDefault`. It does not parse or validate `Version` or `ServerVersion`. [Launching](Launching.md) describes `DisabledByDefault`.

## Hyinit fields

- **`Mixins`**: string or array of strings. Default: `[]`.
  Paths to Mixin configs relative to the JAR root.
- **`HyinitMixinCompatibility`**: string of the form `0.x.y`. Default: `"0.17.5"`.
  Selects Fabric Mixin behaviour for every Mixin config the manifest lists.
  Behaviour changes at `0.10.0`, `0.16.5`, `0.17.0`, `0.17.1`, `0.17.4` and `0.17.5`. There is no lower limit.
  An enabled plugin requesting a newer version than Hyinit supports stops startup.
  Other values, including `0.17.5+mixin.0.8.7`, warn and use the default.
- **`HyinitSharedPackages`**: array of Java package names. Default: `[]`.
  Packages to share between a combined JAR's early and native sides. Includes subpackages.
  Only classes in the JAR itself are shared. Has no effect without `Main`.
  One invalid entry makes Hyinit ignore the whole list, with a warning.
- **`Hyxin.Configs`**: string or array of strings inside a `Hyxin` object. Default: absent.
  Compatibility fallback for Mixin config paths.
  Used only when `Mixins` is absent. A present `Mixins`, including `[]`, takes precedence.

## Compile dependencies

Declare only the dependencies your code uses. Hyinit provides these libraries at runtime:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
    compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.5")
}
```

For configs and injection usage, see the upstream [Mixin documentation](https://github.com/SpongePowered/Mixin/wiki) and [MixinExtras documentation](https://github.com/LlamaLad7/MixinExtras/wiki).

Two settings inside a Mixin config are separate from `HyinitMixinCompatibility`:

- `minVersion` is compared with Mixin `0.8.7`. A value such as `0.17.5` makes Mixin skip the config, or stop startup if the config is required.
- MixinExtras `@Expression` needs `"mixinextras": {"minVersion": "0.5.0"}`.

## Class loading

Early-plugin and server classes use Hyinit's server loader. Native transformations run before Mixins on eligible server classes.

A combined JAR's native plugin loader normally loads separate class copies. Those copies have distinct type identity and static state. Other plugins that use the JAR's classes get the same copies as its native side.

`HyinitSharedPackages` makes the native side reuse the early copy of selected classes. Keep `Main`, `ServiceLoader` providers and classes that need native resources outside those packages.

A class whose name contains `.mixin.` is always shared when the early side has it, with or without `HyinitSharedPackages`.

Shared classes are not reloaded with the plugin. They keep their static state until the server restarts.

## Config and refmap names

Use unique config, parent-config and refmap names. Mixin looks up refmaps and parent configs by name across all JARs. When config filenames collide:

- Byte-identical configs apply once.
- Configs with disjoint Mixin packages receive separate aliases.
- Different configs with overlapping Mixin packages stop startup. The error identifies both JARs.
- A config that a manifest lists but its JAR does not contain is looked up by file name in the server JAR, Hyinit and all early-plugin JARs. Hyinit warns about this only when another manifest lists the same name.
