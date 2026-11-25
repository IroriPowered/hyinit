# Diagnostics reference

Plugins and tools read what Hyinit loaded and which Mixin changed which method through `cc.irori.hyinit.shared.HyinitDiagnostics`. It is read-only and uses only JDK types, so callers need no compile dependency on Hyinit.

## Finding the class

Look the class up through your own plugin's class loader. The system class loader only finds it in `-jar` mode.

```java
Class<?> api = Class.forName("cc.irori.hyinit.shared.HyinitDiagnostics", false, MyPlugin.class.getClassLoader());
```

`ClassNotFoundException` means Hyinit is absent or too old. Use only `HyinitDiagnostics`. Other Hyinit classes a plugin can see are empty copies, and they are internal and can change in any release. For version ranges outside the running server, use Hytale's own `SemverRange`.

To only check whether Hyinit runs, read the system property `hyinit.version`.

## Methods

All methods are `public static`, safe from any thread and never throw. They do not load classes, read files or wait for class loading. Every result is an unmodifiable copy.

| Method | Returns |
| --- | --- |
| `int apiVersion()` | `2` |
| `Map<String, Object> snapshot()` | The [snapshot](#snapshot) |
| `Map<String, List<Map<String, String>>> mixinsFor(String className)` | Every recorded method of the class with its [Mixin entries](#mixin-entries). Empty if Mixins changed nothing in the class. |
| `List<Map<String, String>> mixinsFor(String className, String methodName)` | The [Mixin entries](#mixin-entries) of every overload with that name. Empty if none. |
| `List<Map<String, String>> mixinsFor(String className, String methodName, String descriptor)` | The [Mixin entries](#mixin-entries) of exactly one overload. Empty if Mixins did not change that overload. A `null` descriptor gives every overload. |
| `Map<String, Map<String, List<Map<String, String>>>> methodMap()` | All classes, in the `mixinsFor(String)` form |
| `String hytaleVersion()` | The Hytale version that [`@IfHytale`](/mod/hyinit/conditions-and-manifest-reference#version-ranges) compares against, or `null` if unknown |
| `boolean hytaleVersionMatches(String range)` | The result `@IfHytale(range)` gives, including the pre-release rule. `false` for an invalid range or an unknown version. |
| `boolean mixinApplied(String mixinClass)` | `true` when a Mixin config lists the class and no condition, requirement, `HyinitChangedTargets` or [config plugin](#config-plugin-refusals) skipped it as a whole. Answers from a Mixin config plugin's `onLoad` on. |

Hyinit fills `sources`, `hytaleVersion`, `changedTargets`, `fingerprintChecks` and the `conditionSkips` of conditions and requirements before Mixin prepares the configs. A Mixin config plugin can therefore read them in `onLoad` and decide in `shouldApplyMixin`. `configs`, the method map and [config plugin refusals](#config-plugin-refusals) follow once classes load.

`className` is a binary name such as `a.b.Outer$Inner`, as `StackTraceElement.getClassName()` returns it, or the internal form with `/`. `descriptor` is a JVM method descriptor such as `(FLjava/lang/Object;)V`, as JFR's `RecordedMethod.getDescriptor()` returns it. The method map grows as classes load.

## Snapshot

| Key | Type | Meaning |
| --- | --- | --- |
| `apiVersion` | Integer | `2` |
| `hyinitVersion` | String | Hyinit version |
| `launchMode` | String | `launcher` when started with `-jar`, `earlyPlugin` when started from an early-plugin directory |
| `mixinVersion` | String | Bundled Mixin version, such as `0.8.7` |
| `fabricMixinVersion` | String | Fabric Mixin compatibility level Hyinit supports, such as `0.17.5` |
| `mixinExtrasVersion` | String | Bundled MixinExtras version, such as `0.5.5` |
| `hytaleVersion` | String | `Implementation-Version` of the server JAR, such as `0.8.0-pre.6`, or `null` |
| `sources` | List of Map | One [source](#sources) per early-plugin JAR |
| `configs` | List of Map | One [config](#configs) per registered Mixin config |
| `conditionSkips` | List of Map | One [condition skip](#condition-skips) per Mixin, handler or field that a condition removed or a config plugin refused |
| `changedTargets` | List of Map | One [changed target](#changed-targets) per changed member of an early-plugin JAR that Hyinit compared |
| `fingerprintChecks` | List of Map | One [fingerprint check](#fingerprint-checks) per early-plugin JAR that Hyinit was asked to compare |
| `problems` | List of Map | [Warnings and errors](#problems) since startup |
| `problemsOmitted` | Long | Problems not kept because the list holds at most 1000 |
| `classesTransformed` | Long | Classes passed to Mixin |
| `transformNanos` | Long | Total time spent in Mixin for those classes, in nanoseconds |
| `applyNanosBySource` | Map of String to Long | [Time spent applying Mixins](#apply-time-per-jar) per JAR file name, in nanoseconds |
| `mixinTargetClasses` | Long | Classes with at least one recorded Mixin method |
| `bootstrapMillis` | Long | Milliseconds from Hyinit's start to Hytale's `main` |

Keys that Hyinit does not know yet hold `null`.

### Apply time per JAR

`applyNanosBySource` holds the time Mixin spent applying Mixins to target classes. Each class counts towards the JARs that have a Mixin for it. When several JARs have Mixins for one class, its time is split equally between them, because Mixin applies them in one pass. Hyinit's own Mixins appear under the Hyinit JAR.

The sum is lower than `transformNanos`. That one also covers preparing the configs, classes without Mixins and reading and writing class files. A JAR is missing until one of its target classes loaded. The map grows as classes load.

### Sources

Only early-plugin JARs that Hyinit loaded are listed. Skipped JARs appear under [problems](#problems).

| Key | Type | Meaning |
| --- | --- | --- |
| `file` | String | File name of the JAR |
| `pluginId` | String | `Group:Name` from `manifest.json`, or `null` |
| `version` | String | `Version` from `manifest.json`, or `null` |
| `hasMain` | Boolean | The JAR declares a native plugin `Main` |
| `mixinsSkipped` | Boolean | The plugin was disabled at startup, so its Mixins were not applied |
| `mixinCompatibility` | String | Declared `HyinitMixinCompatibility`, or `null` |
| `sha256` | String | SHA-256 of the JAR as lowercase hex, or `null` if it could not be read |
| `configs` | List of String | Names of the Mixin configs Hyinit registered from this JAR |

### Configs

| Key | Type | Meaning |
| --- | --- | --- |
| `name` | String | Resource name inside the JAR, so it can contain `/`. A renamed duplicate looks like `other.jar!/name.mixins.json`. |
| `source` | String | File name of the JAR, or the Hyinit JAR for Hyinit's own config |
| `mixinPackage` | String | Mixin package |
| `mixins` | List of String | Mixin class names, or `null` if Hyinit cannot list them |
| `targets` | List of String | Target class names |
| `compatibility` | String | Fabric Mixin compatibility level applied to the config |
| `priority` | Integer | Config priority |
| `required` | Boolean | `"required"` in the config |

### Condition skips

| Key | Type | Meaning |
| --- | --- | --- |
| `mixin` | String | Mixin class name |
| `method` | String | Name of the removed method, or `null` |
| `field` | String | Name of the removed field, or `null` |
| `source` | String | File name of the JAR |
| `kind` | String | `condition` for a failed annotation, `requirement` for a failed `ServerVersion` or `Dependencies`, `changedTarget`, or `configPlugin` |
| `condition` | String | The failed condition: `@IfHytale <range>`, `@IfPlugin <id>`, `@IfPlugin <id> <range>`, `@UnlessPlugin <id>`, `@UnlessPlugin <id> <range>`, [`@IfMixin <name> skipped`](/mod/hyinit/conditions-and-manifest-reference#dependent-mixins), `@IfMixin <name> not registered`, [`HyinitChangedTargets skip: <reason>`](/mod/hyinit/conditions-and-manifest-reference#changed-hytale-methods), `Dependency <id> <range> missing`, `Dependency <id> <range> has <version>`, `Dependency <id> <range> is not a valid range`, `ServerVersion <range> is not a valid range` or [`config plugin <class>`](#config-plugin-refusals) |

#### Config plugin refusals

A Mixin config can name an `IMixinConfigPlugin` whose `shouldApplyMixin` turns Mixins off, for example by a switch in the mod's own config file. Hyinit records a Mixin as `configPlugin` when the plugin refuses it for every target class. `mixinApplied` then returns `false` for it. A Mixin that the plugin refuses for only some of its targets still counts as applied.

Hyinit learns the answers when Mixin applies its first Mixin, which is when Hytale's main class loads. Until then `mixinApplied` returns `true` for such a Mixin. Hyinit asks the plugin a second time for this, so `shouldApplyMixin` must give the same answer each time. These skips are not logged, and [`@IfMixin`](/mod/hyinit/conditions-and-manifest-reference#dependent-mixins) does not see them.

### Changed targets

Hyinit compares the JARs that set [`HyinitChangedTargets`](/mod/hyinit/conditions-and-manifest-reference#changed-hytale-methods), and every JAR under `--check-mixins`. It does so before it prepares the Mixin configs, so both lists are complete when a config plugin's `onLoad` runs. Other JARs have no entries, even when they ship fingerprints.

An empty list alone does not mean that nothing changed. Read the [fingerprint check](#fingerprint-checks) of the JAR first.

| Key | Type | Meaning |
| --- | --- | --- |
| `jar` | String | File name of the JAR |
| `mixin` | String | Mixin class that uses the member |
| `class` | String | Hytale class of the member |
| `member` | String | `name(descriptor)` or `name:descriptor`, or `null` for an `unverified` Mixin or a missing class |
| `kind` | String | `method`, `field`, `at` for a recorded [`@At` target](/mod/hyinit/conditions-and-manifest-reference#changed-hytale-methods), `call` for a used member, or `shadow` for a `@Shadow` member |
| `status` | String | `changed`, `missing` or `unverified` |
| `baseline` | String | Hytale version of the compared baseline, or `null` for `call` and `shadow` |

### Fingerprint checks

| Key | Type | Meaning |
| --- | --- | --- |
| `jar` | String | File name of the JAR, as in `changedTargets` |
| `pluginId` | String | `Group:Name` from `manifest.json`, or `null` |
| `status` | String | `compared`, `notShipped` when the JAR has no fingerprints, or `failed` |
| `baseline` | String | Hytale version of the compared baseline, or `null` unless `status` is `compared` |
| `usedMembers` | String | `checked` when the used member and `@Shadow` check ran, otherwise `failed` |
| `detail` | String | The warnings Hyinit logged for a failure, or `null` |

Changed targets of kind `method`, `field` and `at` are complete for a JAR only when its `status` is `compared`. Those of kind `call` and `shadow` are complete only when `usedMembers` is `checked`. A JAR without an entry was not compared.

### Problems

| Key | Type | Meaning |
| --- | --- | --- |
| `level` | String | `warn` or `error` |
| `message` | String | The message Hyinit logged, with file names in place of paths |
| `source` | String | File name of the JAR to blame, or `null` when Hyinit cannot tell |
| `detail` | String | Exception types and messages of the cause chain on one line, or `null` |
| `stackTrace` | String | The exception with its stack frames, or `null` |

`stackTrace` has the format of Java's `printStackTrace` with file names in place of paths. It keeps at most 64 frames per exception and writes `... N frames not kept` for the rest. Only the first 50 problems with an exception have one.

Problems cover early-plugin JAR warnings at startup, conflicting classes, Mixin conflicts on one method, changed Hytale methods found by `--check-mixins` or `HyinitChangedTargets`, skipped Mixins of a failed optional config, failed Mixin transformations and Mixins of a disabled plugin enabled later. They also include every warning and error with an exception that Mixin logs itself. Hyinit never lists a failure twice.

## Mixin entries

| Key | Meaning |
| --- | --- |
| `mixin` | Mixin class name |
| `config` | Name of its Mixin config, or `null` if no registered config owns the Mixin package |
| `source` | File name of the JAR, or `null` |
| `kind` | `merged` or `changed` |
| `descriptor` | JVM descriptor of the method this entry belongs to, such as `(FLjava/lang/Object;)V` |
| `firstLine` | Lowest line number of that method as a decimal string, or `null` if the method has no line numbers |
| `lastLine` | Highest line number of that method as a decimal string, or `null` |

`merged` means the method is Mixin code in the target class: an injector handler, a `@Unique` method, an `@Overwrite` or a lambda from a Mixin. `changed` means an original method calls Mixin code, for example after `@Inject`, `@Redirect` or `@WrapOperation`.

Every value is a String. A list holds one entry per overload, Mixin and kind. A Mixin that changes two overloads of a name therefore appears twice in the lists that go by name, with different descriptors.

## Finding the overload of a frame

A Mixin that changes `tick(F)V` says nothing about `tick(FLjava/lang/Object;)V`. Ask for the exact overload whenever you can.

- A JFR frame carries the descriptor. Pass `RecordedMethod.getDescriptor()` to `mixinsFor(className, methodName, descriptor)`.
- A `StackTraceElement` carries no descriptor. Call `mixinsFor(className, methodName)` and keep the entries whose `firstLine` to `lastLine` range holds `getLineNumber()`. Keep every entry when the frame has no line number or the entry has no lines. A frame inside code that `@Inject` put at the head of a method has no line number.

`firstLine` and `lastLine` are the line numbers of the loaded class, the same numbers a stack trace shows. Mixin moves the lines of merged Mixin code behind the lines of the target class, so they do not match the Mixin's source file.

An overload that no Mixin changed has no entries. A frame that matches no entry after this filter is in such an overload.

## Limits

- A method that only calls a `@Unique` helper of another Mixin also counts as `changed`. Label such frames "changed by Mixin", not as the cause of a problem.
- An injector that failed in an optional config leaves its handler in the class. It is listed as `merged` although it is never called.
- No value contains a file path. A `/` can still appear in config names, in web links inside `detail` and `stackTrace`, and before a frame's class name, as in `java.base/java.lang.Thread.run(Thread.java:1474)`.

## Stability

- Within an `apiVersion`, keys and methods keep their meaning and keys are only added.
- Ignore keys you do not know, and treat a missing key or a `null` value as unknown.

## Example

This lists the Mixins that touched the method of a stack frame. Without Hyinit, `Class.forName` throws and the frame gets no label.

```java
Class<?> api = Class.forName("cc.irori.hyinit.shared.HyinitDiagnostics", false, MyPlugin.class.getClassLoader());
List<Map<String, String>> entries = (List<Map<String, String>>) api.getMethod("mixinsFor", String.class, String.class)
        .invoke(null, frame.getClassName(), frame.getMethodName());
int line = frame.getLineNumber();
List<Map<String, String>> exact = entries.stream()
        .filter(entry -> line <= 0
                || entry.get("firstLine") == null
                || (Integer.parseInt(entry.get("firstLine")) <= line && line <= Integer.parseInt(entry.get("lastLine"))))
        .toList();
```

For a JFR frame, ask for the overload directly.

```java
RecordedMethod method = recordedFrame.getMethod();
List<Map<String, String>> entries = (List<Map<String, String>>) api.getMethod("mixinsFor", String.class, String.class, String.class)
        .invoke(null, method.getType().getName(), method.getName(), method.getDescriptor());
```
