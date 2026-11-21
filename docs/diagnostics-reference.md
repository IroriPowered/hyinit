# Diagnostics reference

Hyinit records what it loaded at startup and which Mixin changed which method. Plugins and tools read it through `cc.irori.hyinit.shared.HyinitDiagnostics`. The interface is read-only and uses only JDK types, so callers need no compile dependency on Hyinit.

## Finding the class

Look the class up through your own plugin's class loader. The system class loader only finds Hyinit in `-jar` mode.

```java
Class<?> api = Class.forName("cc.irori.hyinit.shared.HyinitDiagnostics", false, MyPlugin.class.getClassLoader());
```

`ClassNotFoundException` means Hyinit is absent or too old. Treat that as no data.

Use only `HyinitDiagnostics`. Other Hyinit classes a plugin can see are empty copies.

A tool that only needs to know whether Hyinit runs can read the system property `hyinit.version`.

## Methods

All methods are `public static`, safe from any thread and never throw. They do not load classes, read files or wait for class loading. Every result is an unmodifiable copy.

| Method | Returns |
| --- | --- |
| `int apiVersion()` | `1` |
| `Map<String, Object> snapshot()` | The [snapshot](#snapshot) |
| `Map<String, List<Map<String, String>>> mixinsFor(String className)` | Every recorded method of the class with its [Mixin entries](#mixin-entries). Empty if Mixins changed nothing in the class. |
| `List<Map<String, String>> mixinsFor(String className, String methodName)` | The [Mixin entries](#mixin-entries) of one method. Empty if none. |
| `Map<String, Map<String, List<Map<String, String>>>> methodMap()` | All classes, in the `mixinsFor(String)` form |

`className` is a binary name as `StackTraceElement.getClassName()` returns it, such as `a.b.Outer$Inner`. The internal form with `/` works too. `methodName` is the plain name without a descriptor.

A class enters the method map once Mixin has applied to it, so the map grows while classes load.

## Snapshot

| Key | Type | Meaning |
| --- | --- | --- |
| `apiVersion` | Integer | `1` |
| `hyinitVersion` | String | Hyinit version |
| `launchMode` | String | `launcher` when started with `-jar`, `earlyPlugin` when started from an early-plugin directory |
| `mixinVersion` | String | Bundled Mixin version, such as `0.8.7` |
| `fabricMixinVersion` | String | Fabric Mixin compatibility level Hyinit supports, such as `0.17.5` |
| `mixinExtrasVersion` | String | Bundled MixinExtras version, such as `0.5.5` |
| `sources` | List of Map | One [source](#sources) per early-plugin JAR |
| `configs` | List of Map | One [config](#configs) per registered Mixin config |
| `problems` | List of Map | [Warnings and errors](#problems) since startup |
| `problemsOmitted` | Long | Problems not kept because the list holds at most 1000 |
| `classesTransformed` | Long | Classes passed to Mixin |
| `transformNanos` | Long | Total time spent in Mixin for those classes, in nanoseconds |
| `mixinTargetClasses` | Long | Classes with at least one recorded Mixin method |
| `bootstrapMillis` | Long | Milliseconds from Hyinit's start to Hytale's `main` |

Keys that Hyinit does not know yet hold `null`.

### Sources

Only early-plugin JARs that Hyinit loaded are listed. JARs in `mods` are not. Skipped JARs appear under [problems](#problems).

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

### Problems

| Key | Type | Meaning |
| --- | --- | --- |
| `level` | String | `warn` or `error` |
| `message` | String | The message Hyinit logged, with file names in place of paths |
| `source` | String | File name of the JAR to blame, or `null` when Hyinit cannot tell |
| `detail` | String | Exception types and messages of the cause chain on one line, or `null` |
| `stackTrace` | String | The exception with its stack frames, or `null` |

`stackTrace` has the format of Java's `printStackTrace`, with file names in place of paths. Hyinit keeps at most 64 frames per exception and writes `... N frames not kept` for the rest. Only the first 50 problems with an exception have a stack trace. Later ones have `null`.

Problems cover early-plugin JAR warnings at startup, conflicting classes, skipped Mixins of an optional config that failed, failed Mixin transformations and Mixins of a disabled plugin that is enabled later. They also include every warning and error with an exception that Mixin logs itself, with Mixin's message. Hyinit never lists a failure twice.

## Mixin entries

| Key | Meaning |
| --- | --- |
| `mixin` | Mixin class name |
| `config` | Name of its Mixin config, or `null` if no registered config owns the Mixin package |
| `source` | File name of the JAR, or `null` |
| `kind` | `merged` or `changed` |

`merged` means the method is Mixin code in the target class: an injector handler, a `@Unique` method, an `@Overwrite` or a lambda from a Mixin. `changed` means an original method calls Mixin code, for example after `@Inject`, `@Redirect` or `@WrapOperation`.

## Limits

- Stack frames carry no method descriptor, so overloads of a method share one list.
- A method that only calls a `@Unique` helper of another Mixin also counts as `changed`. Label such frames "changed by Mixin", not as the cause of a problem.
- An injector that failed in an optional config leaves its handler in the class. It is listed as `merged` although it is never called.
- No value contains a file path. A `/` can still appear in config names, in web links inside `detail` and `stackTrace`, and before a frame's class name, as in `java.base/java.lang.Thread.run(Thread.java:1474)`.

## Stability

- Within `apiVersion` 1, keys and methods keep their meaning and keys are only added.
- Ignore keys you do not know, and treat a missing key or a `null` value as unknown.

## Example

This labels a stack frame with the Mixins that touched its method. It works with or without Hyinit.

```java
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

final class MixinLabels {

    private static final Method MIXINS_FOR = find();

    private static Method find() {
        try {
            Class<?> api = Class.forName(
                    "cc.irori.hyinit.shared.HyinitDiagnostics", false, MixinLabels.class.getClassLoader());
            if ((int) api.getMethod("apiVersion").invoke(null) < 1) {
                return null;
            }
            return api.getMethod("mixinsFor", String.class, String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static String label(StackTraceElement frame) {
        if (MIXINS_FOR == null) {
            return "";
        }
        try {
            List<Map<String, String>> entries =
                    (List<Map<String, String>>) MIXINS_FOR.invoke(null, frame.getClassName(), frame.getMethodName());
            StringBuilder label = new StringBuilder();
            for (Map<String, String> entry : entries) {
                label.append(" [Mixin ").append(entry.get("mixin")).append(' ').append(entry.get("kind"))
                        .append(" from ").append(entry.get("source")).append(']');
            }
            return label.toString();
        } catch (ReflectiveOperationException | ClassCastException e) {
            return "";
        }
    }
}
```
