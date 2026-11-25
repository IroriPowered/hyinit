# Troubleshooting

`<...>` marks the parts of a message that change. A trailing `...` marks a shortened message. Unless a row says it stops startup, Hyinit logs the message and continues. Same as above means the fix of the row above applies.

## Server JAR

| Message | Cause and fix |
| --- | --- |
| `Could not locate HytaleServer. Specify the path using --server-jar` | Stops startup. No server JAR in the working directory or beside Hyinit. Put `HytaleServer.jar` beside Hyinit or pass `--server-jar`. |
| `Server JAR not found: <path>` | Stops startup. Correct the `--server-jar` path. |
| `Found <count> server JARs in <directory>: <jar>, <jar>. Keep one or choose one with --server-jar.` | Stops startup. None of them is named `HytaleServer.jar`. |
| `Cannot read the manifest of <server jar> to find the Hytale version` | Every `@IfHytale` element is skipped. Make the server JAR readable. |
| `The Hytale version of <server jar> is unknown ...` | The server JAR has no usable `Implementation-Version`, so every `@IfHytale` element is skipped. Use an official server JAR. |
| `Hyinit could not read the core plugin IDs of <server jar>: <classes>. @IfPlugin and @UnlessPlugin on Hytale:<Name> IDs may not match.` | Conditions treat those `Hytale:<Name>` plugins as absent. Update Hyinit. |
| `Hyinit could not patch <class>: <effect>` | Hyinit starts the server without that minor feature. Update Hyinit. |

## Plugins

| Message | Cause and fix |
| --- | --- |
| `Duplicate early plugin ID <Group:Name> in <jar> and <jar>. Remove one of the JARs.` | Stops startup. See [Duplicate plugins](/mod/hyinit/installing-hyinit#duplicate-plugins). |
| `Skipping <jar>. Hyinit loads Hyxin mods itself, so Hyxin is not needed and can be removed.` | Remove Hyxin. |
| `Cannot read the early-plugin directory <directory>: <error>` | Stops startup. Make the directory readable by the user that runs the server. |
| `Failed to compute the SHA-256 of <jar>` | [Diagnostics](/mod/hyinit/diagnostics-reference) lists the JAR without a SHA-256. Make the JAR readable by the user that runs the server. |
| `<jar> declares HyinitMixinCompatibility <version>, but this Hyinit supports Fabric Mixin <latest>. Update Hyinit.` | Stops startup. Update Hyinit, or disable the plugin until you can. |
| `Mixins of <plugin> were not applied at startup because it was disabled. Restart the server to apply them.` | Restart the server. |
| `<jar> and <jar> contain different versions of <count> classes (<class>, <class>). ...` | Usually an unrelocated shaded library. Remove one JAR or ask its author to relocate the library. |
| `<count> conflicting early plugin JAR pair(s) found and -Dhyinit.failOnClassConflicts=true is set. See the errors above.` | Stops startup. Resolve each conflict above, or remove the option. |
| `Skipping the Mixins of <jar>. It requires <Group:Name> <range>, which is not installed or not enabled. Install or enable <Group:Name>.` | See [Required plugins](/mod/hyinit/conditions-and-manifest-reference#required-plugins). Install or enable the dependency, or remove the JAR. |
| `Skipping the Mixins of <jar>. It requires <Group:Name> <range>, but <Group:Name> has <version>. Update <Group:Name> or <jar>.` | `no version` means the dependency's manifest has none. |
| `<jar> requires <Group:Name> <range>, which is not installed or not enabled. Its Mixins still apply, since Hytale does not check an early-only JAR. Install or enable <Group:Name>.` | The JAR has no `Main`, so nothing is skipped. Install or enable the dependency if the Mixins need it. The message can also end with `but <Group:Name> has <version>`. |
| `<jar> targets Hytale <range>, not <version>. Its Mixins still apply. Check for an update of <jar>.` | Hytale also loads the plugin. Test it, or update the JAR. |
| `Skipping the Mixins of <jar>. Its Dependencies entry <Group:Name> <range> is not a valid version range, so Hytale cannot load it. Ask the author to fix the manifest.` | Ask the author to fix the manifest. In a JAR with a `Main`, Hytale fails to read the manifest and Hyinit skips the Mixins. In an early-only JAR Hyinit ignores the value. |
| `Skipping the Mixins of <jar>. Its ServerVersion <range> is not a valid version range, so Hytale cannot load it. Ask the author to fix the manifest.` | Same as above. |
| `Ignoring the Dependencies entry <Group:Name> <range> in <jar>. It is not a valid version range.` | Same as above. |
| `Ignoring the ServerVersion <range> of <jar>. It is not a valid version range.` | Same as above. |
| `Ignoring <jar> for @IfPlugin and Dependencies. Its manifest.json cannot be read.` | The JAR is in `mods` or a `--mods` directory. Hyinit treats it as absent and Hytale fails to load it. Fix or remove the JAR. |

## Mixins

| Message | Cause and fix |
| --- | --- |
| `<Mixin error>. Update or remove <jar>.` | Usually the JAR was built for another Hytale version. Run [`--check-mixins`](/mod/hyinit/installing-hyinit#check-mixins-after-an-update) after you update it. |
| `Mixin transformation of <class> failed (applied by <jar>, <jar>)` | The brackets list every JAR with a Mixin for that class, including Hyinit. The stack trace names the Mixin that failed. Update or remove its JAR. |
| `This crash passed through <class>.<method> changed by <mixin> from <jar>.` | Follows the trace of an uncaught crash. Report the crash with the trace to the JAR's author first. |
| `Mixin config <config> in <jar> requires <mixin>, which is not in that JAR. Update or remove <jar>.` | Stops startup. |
| `Mixin config <config> in <jar> has the same name as the one in <jar> and an overlapping mixin package. Give each config a unique name.` | Stops startup. Remove one JAR until its author renames the config. |
| `Mixin config <config> in <jar> has the same name as the one in <jar>. It is applied as <alias>. ...` | Both configs apply. |
| `Mixins from <jar> and <jar> conflict on <class>.<method>: @<kind> from <jar>, @<kind> from <jar>. They may not work together.` | Logged once per method. Two JARs both `@Overwrite` a method, both `@Redirect` the same call, or one overwrites a method the other injects into. Mixin keeps only one overwrite or redirect, and the injector no longer sees the original code. Several `@Inject`, `@WrapOperation` or `@WrapMethod` on one method do not count. Report it to both authors or remove one JAR. |
| `<mixin> in <jar> names <name> from <jar> in @IfMixin, but @IfMixin only works for Mixins of the same JAR. Hyinit ignores that name. Update <jar>.` | `@IfMixin` only sees Mixins of its own JAR. That name is ignored. Ask the author to remove it or move both Mixins into one JAR. |
| `<mixin> in <jar> names <name> in @IfMixin, but no Mixin config lists that class. It counts as not applied. Update <jar>.` | The name is misspelled. The element is skipped. Ask the author to fix the name. |
| `Conditions removed members of <mixin> in <jar> that kept code still uses: <members>. ...` | That code fails with `NoSuchMethodError` or `NoSuchFieldError` when it runs. Ask the author to give it the same condition. |
| `<class> in <jar> uses <member>, which conditions removed on Hytale <version>. It fails when that code runs. Give it the same condition.` | Same as above. |
| `Skipping <element> of <jar>. Its @IfHytale range "<range>" is not valid. Update <jar>.` | That element is skipped. Ask the author to fix the range. |
| `Skipping <element> of <jar>. Its @IfPlugin range "<range>" for <Group:Name> is not valid. Update <jar>.` | Same as above. |
| `Keeping <element> of <jar>. Its @UnlessPlugin range "<range>" for <Group:Name> is not valid. Update <jar>.` | That element applies. Ask the author to fix the range. |
| `<jar> asks for <Group:Name> <range> in @IfPlugin, but <Group:Name> has no readable version. The condition counts as not matching.` | The plugin's manifest has no usable `Version`. The `@IfPlugin` element is skipped, and the same message with `@UnlessPlugin` means that element applies. Ask the plugin author to set a `Version`. |
| `Hyinit could not check the Mixins of <class> for conflicts` | The Mixins still apply. Report it to Hyinit with the stack trace. |
| `Cannot list the Mixin classes of <config>` | Same as above. |
| `Cannot list the core plugins of <server jar>. @IfPlugin, @UnlessPlugin and Dependencies only see early plugins` | Conditions and `Dependencies` see `Hytale:` plugins as absent. Report it to Hyinit with the stack trace. |
| `Cannot scan <jar> for code that uses members removed by conditions` | Hyinit does not warn about such code in that JAR. The Mixins still apply. |
| `Cannot find the JAR of <mixin> in <jar>, so Hyinit does not scan it for code that uses members removed by conditions` | Same as above. |

## Fingerprints

| Message | Cause and fix |
| --- | --- |
| `Hytale changed <n> method(s) that <jar> was built against, compared with its fingerprints for Hytale <version>. Check that its Mixins still behave.` | From `--check-mixins`, or at startup with `HyinitChangedTargets` set to `warn`. Changed fields add `and <n> field(s)`. The `--report` file lists each one. The Mixins still apply. Test the feature, or ask the author for a build for this Hytale version. |
| `<n> Mixin(s) of <jar> have no fingerprints for Hytale <version>. Check that they still behave.` | Those Mixins apply on the running version but were not recorded in the chosen baseline. `HyinitChangedTargets` set to `skip` skips them. Ask the author for a build with fingerprints for this Hytale version. |
| `<jar> has no fingerprints for Hytale <version> or older. Its Mixins are not compared.` | Every baseline in the JAR is newer than the running Hytale. No Mixin is skipped for a changed method. Ask the author for a build with fingerprints for this Hytale version. |
| `<jar> has fingerprints, but the Hytale version is unknown. Its Mixins are not compared.` | The server JAR has no `Implementation-Version`. Use an official server JAR. |
| `Mixins of <jar> use <n> member(s) that Hytale <version> does not have: <class>.<member>. They fail when that code runs.` | From `--check-mixins`, or at startup with `HyinitChangedTargets` set to `warn`. `skip` skips those Mixins. Ask the author for a build for this Hytale version. |
| `Mixins of <jar> shadow <n> member(s) that Hytale <version> does not have: <class>.<member>. Mixin cannot apply them.` | Same origin. A `@Shadow` field or method is not in its target class, so Mixin refuses the Mixin and a required config stops the class from loading. `skip` skips those Mixins. Ask the author for a build for this Hytale version. |
| `<class> in <jar> uses <class>.<member>, which Hytale <version> does not have. It fails when that code runs.` | From `--check-mixins`, or at startup with `HyinitChangedTargets` set to `warn` or `skip`. Code outside the Mixins uses a member the running Hytale lacks. Ask the author for a build for this Hytale version. |
| `Conditions skipped <count> Mixins of <jar> on Hytale <version>, <count> because Hytale changed what they use` | The JAR sets `HyinitChangedTargets` to `skip` and the server starts without those Mixins. Run `--check-mixins --report <file>` to see each one under `conditionSkips`. Its reason is `Hytale changed <class>.<method> since <baseline>`, `no fingerprints for Hytale <baseline>` or `Hytale <version> has no <class>.<member>`, the last for a used or shadowed member. Ask the author for a build for this Hytale version. |
| `<jar> has fingerprints in an unknown format. Build it again with --write-fingerprints.` | From `--check-mixins`, or at startup with `HyinitChangedTargets` set. No Mixin is skipped for a changed method. Ask the author for a new build. |
| `<jar> has fingerprints from a newer Hyinit. Update Hyinit.` | Same origin and effect. Update Hyinit to the version the author built with. |
| `<jar> sets HyinitChangedTargets but has no fingerprints. Build it with --write-fingerprints.` | Its Mixins apply as usual. Ask the author for a new build. |
| `<server jar> reports Hytale 0.0.0-dev, not a Maven version. Name it with --fingerprint-server=<version>=<server jar>.` | `--write-fingerprints` stops. Source builds do not know their Maven version. Pass it before the path. |
| `<jar> sets HyinitFingerprintAtTargets to <value>, not true or false.` | `--write-fingerprints` stops. Set the key to `true` or `false` in `manifest.json`, or remove it. |
| `Cannot compare the fingerprints in <jar> with <server jar>` | The fingerprints file cannot be read. The stack trace below names the error. No Mixin is skipped for a changed method. Ask the author for a new build. |
| `Cannot check the members that <jar> uses against <server jar>` | A class in the JAR cannot be read. The stack trace below names the error. The Mixins still apply. |
| `Cannot read <server jar> to compare the fingerprints` | No JAR is compared. Make the server JAR readable by the user that runs the server. |
| `Ignoring HyinitChangedTargets in <jar>. "<value>" is not compare, warn or skip.` | Its Mixins apply as usual. Ask the author to fix the manifest. |

## Check Mixins

| Message | Cause and fix |
| --- | --- |
| `<failed> of <total> Mixin target classes failed: <class>, <class>` | Exits with status 1. The errors above show why. Update or remove the JAR with the failing Mixin. |
| `The Mixin configs of <count> JARs could not be prepared: <jar>, <jar>. Update or remove them.` | Exits with status 1. The error lines above name the Mixin and the missing target of each JAR. |
| `<count> Mixin(s) of optional configs could not be applied and were skipped: <mixin>, <mixin>. The warnings above show why.` | Exits with status 1. Their configs do not set `"required": true`, so Mixin dropped them and the target classes loaded without them. The `Mixin apply for mod <jar> failed` warning above each has the reason. Update or remove the JAR. |
| `Loading <class> failed` | The stack trace that follows shows why. |
| `Mixin target <class> was not found.` | Not counted as a failure. |
| `<jar>: not prepared, see its error` | Summary line of `--check-mixins`. The error names the Mixin and the missing target. Update or remove the JAR. |
| `<jar>: not readable, see the warning above` | Summary line. The `Failed to read <jar>` warning above has the reason. Replace the JAR. |
| `<count> warning(s) count as failures with --strict: <warning>` | Exits with status 1. Fix the warnings above or drop `--strict`. |
| `--strict only works with --check-mixins` | Add `--check-mixins` or drop the option. |
| `--report only works with --check-mixins` | Same as above. |
| `--report needs the path of the report file` | Exits with status 1. Put the path after the option. |
| `--write-fingerprints needs the path of the mod JAR` | Same as above. |
