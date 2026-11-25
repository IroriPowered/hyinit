# Conditions and manifest reference

## Conditions

Annotations in `cc.irori.hyinit.api` skip a whole Mixin class or single members depending on the Hytale version and on which plugins are present. They are in the Hyinit JAR and in the small `hyinit-api-0.5.0.jar`. Both are attached to the [GitHub release](https://github.com/IroriPowered/hyinit/releases). Put either JAR in `libs` and add it as a `compileOnly` dependency:

```kotlin
compileOnly(files("libs/hyinit-api-0.5.0.jar"))
```

The API may change before Hyinit 1.0. The annotations need no runtime classes and work with or without a config `plugin`.

| Annotation | Keeps the Mixin or member when |
| --- | --- |
| `@IfHytale("range")` | The server version is in the [range](#version-ranges) |
| `@IfPlugin("Group:Name")` | The [plugin](#plugin-conditions) is present and enabled |
| `@IfPlugin(value = "Group:Name", version = "range")` | The plugin is present and enabled and its version is in the range |
| `@UnlessPlugin("Group:Name")` | The plugin is not present and enabled |
| `@UnlessPlugin(value = "Group:Name", version = "range")` | The plugin is not present and enabled or its version is outside the range |
| `@IfMixin({"com.example.mixin.MixinName", ...})` | Experimental. Every listed Mixin class of the same JAR applies. See [Dependent Mixins](#dependent-mixins) |

On a Mixin class, a failing condition skips the whole Mixin, including Mixins that a config `plugin` adds through `getMixins()`. On a method or field, it removes that member. Use it on injector handlers, `@Shadow` methods and fields, `@Accessor` and `@Invoker` methods and `@Unique` helpers. Several annotations on one element must all pass. To drop one overload, put the condition on that overload only.

```java
@Mixin(targets = "com.hypixel.hytale.server.core.HytaleServer", remap = false)
public class MixinHytaleServer {
    @IfHytale("<0.8.0")
    @Inject(method = "boot", at = @At(value = "INVOKE", target = "Lcom/example/Old;call()V"))
    private void examplemod$bootOld(CallbackInfo ci) {}

    @IfHytale(">=0.8.0-pre.1")
    @Inject(method = "boot", at = @At(value = "INVOKE", target = "Lcom/example/New;call()V"))
    private void examplemod$bootNew(CallbackInfo ci) {}
}
```

Removing a member does not change the code that calls it. Give every caller the same `@IfHytale`, `@IfPlugin`, `@UnlessPlugin` or `@IfMixin` on its class or method, or move the version-specific parts into their own Mixin with the condition on the class. Hyinit warns about code in the same JAR that still uses what conditions removed:

- Kept code in the same Mixin that calls a removed method or uses a removed field. It fails with `NoSuchMethodError` or `NoSuchFieldError`.
- Other classes that call a removed `@Accessor`, `@Invoker` or added method, use a removed field, or cast to an accessor interface whose Mixin was skipped. They fail with `AbstractMethodError`, `NoSuchMethodError` or `ClassCastException`.

Classes in other JARs are not checked.

A skip by anything on this page is never an error, also in configs with `"required": true` and with `--check-mixins`. Hyinit logs one line per JAR that counts them, such as `Conditions skipped 84 Mixins, 8 handlers and 1 field of mymod.jar on Hytale 0.8.0-pre.6`. `--check-mixins --report` lists every skipped Mixin, handler and field with the condition that failed. They are also in the diagnostics snapshot under [`conditionSkips`](/mod/hyinit/diagnostics-reference#condition-skips).

### Version ranges

`@IfHytale`, the `version` of `@IfPlugin` and `@UnlessPlugin`, `ServerVersion` and `Dependencies` use Hytale's range syntax with the same results as Hytale's own check:

- Comparators `>=`, `>`, `<=`, `<` and `=`, such as `>=0.7.7`.
- Space-separated comparators must all match, such as `>=0.8.0-pre.5 <0.9.0`.
- `||` separates alternatives, such as `<0.8.0 || >=0.9.0`.
- `^0.8.1`, `~0.8.1`, `0.8.0 - 0.8.4`, `0.8` and `0.8.x` work as in npm.
- `*` matches every version.

Pre-releases follow npm. A range only matches a pre-release such as `0.8.0-pre.6` when one comparator names a pre-release of the same `0.8.0`. `>=0.7.7` does not match `0.8.0-pre.6`, while `>=0.7.7 || >=0.8.0-pre.1` does.

The server version is `Implementation-Version` in the server JAR's `META-INF/MANIFEST.MF`. If it is missing, every `@IfHytale` fails and Hyinit warns once. An invalid `@IfHytale` range skips its element with a warning.

One JAR can support several Hytale lines, such as 0.7 and 0.8. Give each line its own Gradle source set and Mixin package, compiled against the server JAR of that line, and put `@IfHytale` with the range of the line on every class of it. Record one [fingerprint baseline](#changed-hytale-methods) per version with `--fingerprint-server=<version>=<jar>`, so `--check-mixins` compares each line with its own baseline. [Refixes](https://github.com/IroriPowered/refixes) is built this way.

### Plugin IDs

`@IfPlugin`, `@UnlessPlugin` and `Dependencies` see these plugins by their `Group:Name` ID, the key of the `Mods` section in `config.json`:

- Early-plugin JARs that Hyinit loads, unless `config.json` disables them.
- JARs in `mods` and in every directory passed with `--mods`. Hyinit only reads their `manifest.json`. They count as enabled by Hytale's rule: their `Mods` entry decides first, otherwise a plugin without `DisabledByDefault` is enabled when `DefaultModsEnabled` is true. `DefaultModsEnabled` defaults to true on a dedicated server and to false with `--singleplayer`, except in configs older than version 4. A JAR whose manifest cannot be read is ignored with a warning.
- Hytale's built-in plugins, unless `config.json` disables them. These are the core modules such as `Hytale:AssetModule` and `Hytale:Universe` and the plugins in the server JAR's `manifests.json` such as `Hytale:Teleport`.
- The `SubPlugins` of each of these, as `<Group or parent Group>:<Name>` with their own `Version` or the parent's. A sub-plugin counts as missing when its parent does.

Hyinit decides this once at startup. Conditions do not change when Hytale refuses a plugin later.

### Plugin conditions

Each `@IfPlugin` and `@UnlessPlugin` names one plugin. `version` defaults to `*`, which matches any version. Repeat the annotation for more plugins:

```java
@IfPlugin("Hytale:AssetModule")
@IfPlugin(value = "Example:Lib", version = ">=1.2.0")
@UnlessPlugin(value = "Example:Old", version = "<2.0.0")
```

Every `@IfPlugin` must match, and any matching `@UnlessPlugin` skips the element. The version is the one [`Dependencies`](#required-plugins) check. A plugin without a readable version only matches `*`. Any other range counts as not matching and Hyinit warns once. An invalid range skips an `@IfPlugin` element and keeps an `@UnlessPlugin` element, both with a warning.

### Required plugins

The `Dependencies` of a JAR with a `Main` apply to its Mixins too. When a required plugin is not installed, is disabled or has a `Version` outside the range, Hytale refuses the native plugin, so Hyinit skips every Mixin of the JAR with a warning. A JAR skipped this way counts as missing for the JARs that require it, and so do its sub-plugins. `@IfPlugin` and `@UnlessPlugin` still see its ID when another loaded JAR provides it.

Hytale never reads the manifest of an early-only JAR, so Hyinit skips nothing there. An unmet `Dependencies` entry only logs a warning and the Mixins still apply. Use `@IfPlugin` on the Mixins that need another plugin.

- Core modules have the server version. Plugins from the server JAR's `manifests.json` have the `Version` of their manifest, such as `1.0.0` for `Hytale:Teleport`.
- An early-only JAR can name `IroriPowered:Hyinit` with a range that is compared with the running Hyinit, and `Build-9:Hyxin` with any range. A JAR with a `Main` cannot require either, since Hytale does not load them as plugins.
- A JAR with a `Main` only sees plugins that Hytale loads natively, so early-only JARs do not satisfy it.
- An invalid range skips the Mixins of a JAR with a `Main`, since Hytale cannot load that manifest. In an early-only JAR it is ignored with a warning.
- `OptionalDependencies` never skip anything.

### Dependent Mixins

`@IfMixin` is experimental and may change before Hyinit 1.0. It names other Mixin classes of the same JAR by their fully qualified name. The element applies only if a Mixin config of that JAR lists every named Mixin and nothing on this page skipped them. A skip cascades along a chain of `@IfMixin`. Mixins that name each other apply together or are skipped together. A name that no Mixin config lists counts as not applied, with a warning. A Mixin class of another JAR is ignored, with a warning.

```java
@IfMixin("com.example.mixin.MixinChunkSaver")
@Mixin(targets = "com.hypixel.hytale.server.core.universe.world.storage.ChunkStore", remap = false)
public class MixinChunkSaverHooks {}
```

Use it when one Mixin calls code that another Mixin adds. `HyinitChangedTargets` only skips the Mixins that touch a changed method, not the Mixins that rely on them. Both Mixins must be in your JAR.

### Changed Hytale methods

Record fingerprints into the built JAR against every server JAR you tested with:

```shell
java -jar Hyinit-X.X.X.jar --write-fingerprints build/libs/my-plugin.jar \
    --fingerprint-server Server-0.7.7.jar --fingerprint-server Server-0.8.0-pre.6.jar
```

This writes `META-INF/hyinit/fingerprints.json` into the JAR with one baseline per Hytale version. It hashes the bytecode of every Hytale method and constructor your Mixins overwrite, inject into or call through `@Invoker`. Line numbers, stack map frames and constant pool order do not count. For fields used through `@Accessor` it records the owner, name, type and modifiers. An interrupted run leaves the old JAR intact. Run it again after each release build. `--server-jar` still works for a single server JAR. As the last action of the `jar` task in `build.gradle.kts`, Gradle keeps the fingerprinted JAR as the task output:

```kotlin
tasks.jar {
    doLast {
        providers.exec {
            commandLine("java", "-jar", "Hyinit-X.X.X.jar", "--write-fingerprints", archiveFile.get().asFile.path,
                "--fingerprint-server", "Server-0.7.7.jar", "--fingerprint-server", "Server-0.8.0-pre.6.jar")
        }.result.get()
    }
}
```

Each baseline is keyed by the `Implementation-Version` of its server JAR. A server JAR built from source reports `0.0.0-dev`, so name its Maven version yourself with `--fingerprint-server=0.8.0-pre.6=HytaleServer.jar`. A JAR without a version or with `0.0.0-dev` stops the run with an error.

For each server JAR, Hyinit evaluates `@IfHytale` and `@IfMixin` with that version on Mixin classes, handlers and fields, and records only what applies there. Every Mixin that applies is listed, even one that touches no recorded member. `@IfPlugin` and `@UnlessPlugin` are not evaluated when writing, so Mixins and handlers they guard are recorded as if they apply.

[`--check-mixins`](/mod/hyinit/installing-hyinit#check-mixins-after-an-update) compares each JAR that has fingerprints with the baseline of the running Hytale version. Without one it uses the newest baseline older than the running version. Without that either, it warns once and does not compare the JAR. A Mixin that applies on the running version but is missing from the chosen baseline counts as `unverified`. A recorded method that no longer exists counts as `missing`. If the Mixin's selector still matches a method of the same name in that class, for example a name-only selector after a parameter was added, it counts as `changed`, because Mixin still applies there. The log and the `--report` file name the chosen baseline.

`--check-mixins` also checks that every Hytale method, field and class a JAR's code uses still exists in the running server JAR, looking through superclasses and interfaces. Code skipped by its own conditions is not checked. A missing member used by a Mixin counts as a changed target of that Mixin with status `missing`. A missing member used by other code gets one warning per class. This check needs no fingerprints.

The same check looks up each `@Shadow` field and method of a Mixin in every target class that the server JAR has. Mixin looks only in the target class itself, not in its superclasses, so Hyinit does too. A shadow method's `prefix` is removed first, and any of its `aliases` may match. A shadow also passes when another Mixin of the same JAR that applies adds the member to that target. Shadows skipped by their own conditions are not checked. A shadow that is not found counts as a changed target of kind `shadow` with status `missing`, because Mixin refuses to apply that Mixin.

At a normal start Hyinit does not read fingerprints. A JAR opts in with `HyinitChangedTargets` in its `manifest.json`:

```json
"HyinitChangedTargets": "skip"
```

Every value makes Hyinit compare the fingerprints of that JAR and check its used members at each start, before it prepares the Mixin configs. The result is in the diagnostics snapshot under [`changedTargets`](/mod/hyinit/diagnostics-reference#changed-targets) and [`fingerprintChecks`](/mod/hyinit/diagnostics-reference#fingerprint-checks).

- `"compare"` does only that. It logs no changes and skips nothing. Use it when your own code reads the snapshot and decides.
- `"warn"` also logs the `--check-mixins` warnings at startup. The Mixins still apply.
- `"skip"` also skips every Mixin class of the JAR that overwrites, injects into or redirects a method that changed or no longer exists, whose `@Accessor` or `@Invoker` targets such a field, method or constructor, that is `unverified`, that uses a missing member or that shadows one. The rest of the JAR still loads, so make sure it works without those Mixins.

Over the full Hytale history about one in three Mixins flagged by fingerprints had a real behaviour change. So `"skip"` turns off about two working Mixins for every real change it catches.

When the comparison cannot run, Hyinit logs one warning, reports the status `failed` and skips no Mixin for a changed method. That happens with fingerprints in an unknown or newer format, without a baseline for the running Hytale version or an older one, and with an unknown Hytale version. The used member check still runs, and `"skip"` still skips Mixins that use a missing member. A JAR that sets the key without fingerprints gets a warning and the status `notShipped`. Another value gets a warning and starts as if the key were absent.

`@FingerprintAtTargets` on a Mixin class also records the body of each member that its injectors name in `@At` with `INVOKE`, `INVOKE_ASSIGN`, `FIELD` or `NEW`, through `@Desc`, or in a MixinExtras `@Definition`. The member is looked up in its declaring class through superclasses and interfaces. Members not in the server JAR are left out. A change shows with kind `at`. It is opt-in because over the full Hytale history 2 of 12 sampled flags from these bodies were relevant. `"HyinitFingerprintAtTargets": true` in `manifest.json` records them for every Mixin class of the JAR, with or without the annotation. With `"skip"` every changed `@At` target also skips its Mixin, so expect more Mixins skipped for changes that do not matter to them.

## manifest.json reference

### Hytale fields

| Field | Type | Meaning |
| --- | --- | --- |
| `Group`, `Name` | string | Form the plugin ID `Group:Name`. |
| `Version` | string | The plugin's semantic version. Hyinit does not check it. |
| `ServerVersion` | string | Supported [server versions](#server-version). `*` allows any version. |
| `Main` | string | The fully qualified native plugin entry point. Omit it for an early-only JAR. A class that does not extend `JavaPlugin`, such as a class transformer, is not loaded as a native plugin. |
| `DisabledByDefault` | boolean | Disable the plugin unless `config.json` enables it. Hyinit then skips its Mixins. |
| `Dependencies` | object of `"Group:Name": "range"` | [Required plugins](#required-plugins). Hytale checks them only in a JAR with a `Main`. |
| `OptionalDependencies` | object of `"Group:Name": "range"` | Plugins that load first when present. Hyinit ignores them. |

### Server version

Hyinit treats `ServerVersion` exactly as Hytale does and is never stricter:

- A range that does not match the server version only warns. Hytale also loads the plugin, so the Mixins still apply.
- A missing `ServerVersion` or a legacy `YYYY.MM.DD-<sha>` value is accepted. Hytale reads the legacy form as `*`.
- An invalid range, such as a bare `0.5.1`, makes Hytale fail to read the manifest of a JAR with a `Main`, so Hyinit skips all Mixins of that JAR with a warning. In an early-only JAR Hyinit ignores the value with a warning.
- `Hytale:` plugins are not checked, like in Hytale.

### Hyinit fields

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `Mixins` | string or array of strings | `[]` | [Mixin configs](#mixins) |
| `HyinitMixinCompatibility` | string `0.x.y` | `"0.17.5"` | [Fabric Mixin behaviour](#hyinitmixincompatibility) |
| `HyinitSharedPackages` | array of package names | `[]` | [Packages shared with the native plugin](#hyinitsharedpackages) |
| `HyinitChangedTargets` | `"compare"`, `"warn"` or `"skip"` | absent | [Reaction to changed Hytale methods](#changed-hytale-methods) |
| `HyinitFingerprintAtTargets` | boolean | `false` | [Record `@At` targets of every Mixin](#changed-hytale-methods) |

#### Mixins

Paths to Mixin configs relative to the JAR root.

The manifest is the only way to register a Mixin config. Hyinit does not support Mixin's `IMixinConnector` and ignores the `MixinConnector` and `MixinConfigs` attributes of `META-INF/MANIFEST.MF`. A config that a connector adds would skip Hyinit's checks for duplicate config names and for required Mixin classes that are missing from the JAR, and Hyinit could not tell which JAR it came from. To turn Mixins on and off from your own code, name a `plugin` in the config and answer in `shouldApplyMixin`.

Plugins written for Hyxin can keep `"Hyxin": {"Configs": ...}`, a string or an array like `Mixins`. Hyinit reads it only when `Mixins` is absent. `[]` counts as present.

#### HyinitMixinCompatibility

Selects Fabric Mixin behaviour for every Mixin config the manifest lists. Behaviour changes at `0.10.0`, `0.16.5`, `0.17.0`, `0.17.1`, `0.17.4` and `0.17.5`. There is no lower limit.

An enabled plugin requesting a newer version than Hyinit supports stops startup. Values that are not `0.x.y`, such as `0.17.5+mixin.0.8.7`, warn and use the default.

#### HyinitSharedPackages

A combined JAR's native plugin loads its own copies of the JAR's classes, with their own static state. Mixins see the early copies. Other plugins get the native copies.

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
