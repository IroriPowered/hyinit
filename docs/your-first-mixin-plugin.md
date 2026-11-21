# Your first Mixin plugin

This guide builds an early-only plugin: a JAR with one Mixin and no native plugin. It prints a line before the Hytale server's `main` method runs.

## Project setup

Use Gradle with Java 25. Hyinit provides Mixin and MixinExtras at runtime, so they are `compileOnly`, like the Hytale server.

`build.gradle.kts`:

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://maven.hytale.com/release")
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:0.7.7")
    compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
}
```

Add `compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.5")` only if you use MixinExtras.

## manifest.json

`src/main/resources/manifest.json`:

```json
{
  "Group": "Example",
  "Name": "HelloMixin",
  "Version": "1.0.0",
  "Mixins": ["hellomixin.mixins.json"]
}
```

The [`manifest.json` reference](#manifestjson-reference) lists every field.

## Mixin config

`src/main/resources/hellomixin.mixins.json`:

```json
{
  "required": true,
  "minVersion": "0.8",
  "compatibilityLevel": "JAVA_21",
  "package": "com.example.hellomixin.mixin",
  "mixins": ["MixinMain"],
  "injectors": {
    "defaultRequire": 1
  }
}
```

Give the config a name no other plugin uses.

With `"required": true` and `"defaultRequire": 1`, a Mixin that no longer applies fails when its target class loads. Without them Mixin only logs it. Keep them on unless your Mixins are optional.

## The Mixin

`src/main/java/com/example/hellomixin/mixin/MixinMain.java`:

```java
package com.example.hellomixin.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.hypixel.hytale.Main", remap = false)
public class MixinMain {
    @Inject(method = "main", at = @At("HEAD"))
    private static void hellomixin$main(String[] args, CallbackInfo ci) {
        System.out.println("Hello from HelloMixin");
    }
}
```

Keep Mixin classes in the config's `package` and nothing else there. Prefix injected method names with your plugin name so they do not clash with other plugins' Mixins on the same class.

The [Mixin wiki](https://github.com/SpongePowered/Mixin/wiki) and the [MixinExtras wiki](https://github.com/LlamaLad7/MixinExtras/wiki) explain `@Inject` and the other injectors.

## Build and run

Create the Gradle wrapper once with `gradle wrapper`, then build:

```shell
./gradlew build
```

Copy `build/libs/*.jar` to `earlyplugins` beside the server and [start the server with Hyinit](/mod/hyinit/installing-hyinit).

After a Hytale update, run [`--check-mixins`](/mod/hyinit/installing-hyinit#check-mixins-after-an-update) to find injectors that no longer match.

## Next steps

If the JAR also contains a native plugin, see [`HyinitSharedPackages`](#hyinitsharedpackages).

## manifest.json reference

### Example

This combined JAR contains both a native plugin and Mixins:

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

### Hytale fields

| Field | Type | Meaning |
| --- | --- | --- |
| `Group`, `Name` | string | Form the plugin ID `Group:Name`. |
| `Version` | string | The plugin's semantic version. |
| `ServerVersion` | string | A server-version range supported by Hytale's native loader. `*` allows any version. |
| `Main` | string | The fully qualified native plugin entry point. Omit it for an early-only JAR. |
| `DisabledByDefault` | boolean | Disable the plugin unless `config.json` enables it. Hyinit then skips its Mixins. |

Hyinit does not check `Version` or `ServerVersion`. A `Main` in the JAR that does not extend `JavaPlugin`, such as a class transformer, is not loaded as a native plugin.

### Hyinit fields

| Field | Type | Default |
| --- | --- | --- |
| `Mixins` | string or array of strings | `[]` |
| `HyinitMixinCompatibility` | string `0.x.y` | `"0.17.5"` |
| `HyinitSharedPackages` | array of package names | `[]` |

#### Mixins

Paths to Mixin configs relative to the JAR root.

Plugins written for Hyxin can keep `"Hyxin": {"Configs": ...}`, a string or an array like `Mixins`. Hyinit reads it only when `Mixins` is absent. `[]` counts as present.

#### HyinitMixinCompatibility

Selects Fabric Mixin behaviour for every Mixin config the manifest lists. Behaviour changes at `0.10.0`, `0.16.5`, `0.17.0`, `0.17.1`, `0.17.4` and `0.17.5`. There is no lower limit.

An enabled plugin requesting a newer version than Hyinit supports stops startup. Values that are not `0.x.y`, such as `0.17.5+mixin.0.8.7`, warn and use the default.

#### HyinitSharedPackages

A combined JAR's native plugin loads its own copies of the JAR's classes. They are separate from the early copies that Mixins see, with their own static state. Other plugins get the native copies.

`HyinitSharedPackages` lists packages whose classes the native side takes from the early side instead. It includes subpackages, covers only classes in the JAR itself and has no effect without `Main`. Keep `Main`, `ServiceLoader` providers and classes that need native resources outside those packages. One invalid entry makes Hyinit ignore the whole list, with a warning.

A class whose name contains `.mixin.` is always shared when the early side has it. Shared classes are not reloaded with the plugin. They keep their static state until the server restarts.

### Mixin config settings

Two settings inside a Mixin config are separate from `HyinitMixinCompatibility`:

- `minVersion` is compared with Mixin `0.8.7`. A value such as `0.17.5` makes Mixin skip the config, or stop startup if the config is required.
- MixinExtras `@Expression` needs `"mixinextras": {"minVersion": "0.5.0"}`.

### Config and refmap names

Use unique config, parent-config and refmap names. Mixin looks up refmaps and parent configs by name across all JARs. When config filenames collide:

- Byte-identical configs apply once.
- Configs with disjoint Mixin packages receive separate aliases.
- Different configs with overlapping Mixin packages stop startup.
- A config that a manifest lists but its JAR does not contain is looked up by file name in the server JAR, Hyinit and all early-plugin JARs. Hyinit warns about this only when another manifest lists the same name.
