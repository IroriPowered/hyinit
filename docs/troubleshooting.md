# Troubleshooting

`<...>` marks the parts of a message that change. A trailing `...` marks a shortened message.

## Server JAR

| Message | Cause and fix |
| --- | --- |
| `Could not locate HytaleServer. Specify the path using --server-jar` | No server JAR in the working directory or beside Hyinit. Put `HytaleServer.jar` beside Hyinit or pass `--server-jar`. |
| `Server JAR not found: <path>` | The `--server-jar` path is not a file. Correct the path. |
| `Found <count> server JARs in <directory>: <jar>, <jar>. Keep one or choose one with --server-jar.` | None of them is named `HytaleServer.jar`. |

## Plugins

| Message | Cause and fix |
| --- | --- |
| `Duplicate early plugin ID <Group:Name> in <jar> and <jar>. Remove one of the JARs.` | See [Duplicate plugins](/mod/hyinit/installing-hyinit#duplicate-plugins). |
| `Skipping <jar>. Hyinit loads Hyxin mods itself, so Hyxin is not needed and can be removed.` | Warning. |
| `Cannot read the early-plugin directory <directory>: <error>` | Make the directory readable by the user that runs the server. |
| `<jar> declares HyinitMixinCompatibility <version>, but this Hyinit supports Fabric Mixin <latest>; update Hyinit.` | Update Hyinit, or disable the plugin until you can. |
| `Mixins of <plugin> were not applied at startup because it was disabled. Restart the server to apply them.` | Warning. |
| `<jar> and <jar> contain different versions of <count> classes (<class>, <class>). ...` | Warning. Usually an unrelocated shaded library. Remove one JAR or ask its author to relocate the library. |
| `<count> conflicting early plugin JAR pair(s) found and -Dhyinit.failOnClassConflicts=true is set. See the errors above.` | Resolve each conflict above, or remove the option. |
| `Failed to compute the SHA-256 of <jar>` | Warning. [Diagnostics](/mod/hyinit/diagnostics-reference) reports the JAR without a SHA-256. Make the JAR readable by the user that runs the server. |
| `Cannot list the Mixin classes of <config>` | Warning. Diagnostics reports the config without its Mixin classes. Mixins are applied as usual. Report it with the stack trace that follows. |

## Mixins

| Message | Cause and fix |
| --- | --- |
| `<Mixin error>. Update or remove <jar>.` | Usually the JAR was built for another Hytale version. Run [`--check-mixins`](/mod/hyinit/installing-hyinit#check-mixins-after-an-update) after you update it. |
| `Mixin transformation of <class> failed (applied by <jar>, <jar>)` | The brackets list every JAR with a Mixin for that class, including Hyinit itself. The stack trace names the Mixin that failed. Update or remove its JAR. |
| `Mixin config <config> in <jar> requires <mixin>, which is not in that JAR. Update or remove <jar>.` | Stops startup. A required config lists a Mixin class its JAR does not contain. |
| `Mixin config <config> in <jar> has the same name as the one in <jar> and an overlapping mixin package. Give each config a unique name.` | Two plugins ship different configs with the same name. Remove one JAR until its author renames the config. |
| `Mixin config <config> in <jar> has the same name as the one in <jar>. It is applied as <alias>. ...` | Warning. The packages do not overlap and both configs are applied. |
| `<failed> of <total> Mixin target classes failed: <class>, <class>` | `--check-mixins` exits with status 1. The errors above show why each class failed. Update or remove the JAR with the failing Mixin. |
| `Loading <class> failed` | From `--check-mixins`. The stack trace that follows shows why. |
| `Mixin target <class> was not found.` | Warning from `--check-mixins`. Not counted as a failure. |
