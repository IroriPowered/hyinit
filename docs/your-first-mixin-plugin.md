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

To be told when Hytale changes a method your Mixins touch, record [fingerprints](/mod/hyinit/conditions-and-manifest-reference#changed-hytale-methods).

## Next steps

- [Conditions](/mod/hyinit/conditions-and-manifest-reference#conditions) ship one JAR for several Hytale versions and plugin sets.
- A JAR that also contains a native plugin needs [`HyinitSharedPackages`](/mod/hyinit/conditions-and-manifest-reference#hyinitsharedpackages).
- The [manifest.json reference](/mod/hyinit/conditions-and-manifest-reference#manifestjson-reference) lists every field.
