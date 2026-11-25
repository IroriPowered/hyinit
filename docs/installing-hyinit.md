# Installing Hyinit

Hyinit has no configuration file. Every way below gives mods the same Mixin setup.

## Install as an early plugin

Put the Hyinit JAR in an early-plugin directory beside the Mixin JARs and start the server as usual. In single-player that directory is `UserData/EarlyPlugins` in the Hytale data folder.

- A dedicated server needs `--accept-early-plugins` or a confirmation on the console. Single-player needs neither.
- Hytale constructs the native class transformers of every JAR, including JARs Hyinit skips. Remove JARs that Hyinit skips.
- `--server-jar` is ignored.

## Start Hyinit instead of the server

Put `HytaleServer.jar` beside the Hyinit JAR and the Mixin JARs in `earlyplugins`, then run:

```shell
java -jar Hyinit-X.X.X.jar --assets=../Assets.zip
```

See [Options](#options).

## Hosts that only start HytaleServer.jar

Rename Hyinit to `HytaleServer.jar` and put the server beside it as `HytaleServer-original.jar`.

## Crash reporting

Hyinit turns off Hytale's built-in crash reporting. Set `SentryDsn` in `config.json` to send reports to your own Sentry project.

## Managing plugins

Put Mixin JARs and combined plugins in `earlyplugins`. [JAR discovery](#jar-discovery) lists the other scanned directories.

### Duplicate plugins

Byte-identical copies of a JAR with the same `Group:Name` ID are loaded once, with a warning. JARs without an ID are not compared. Different JARs with the same ID stop startup, even if their versions match or they are disabled.

A JAR in `earlyplugins` with a `Main` is also loaded as a native plugin. An identical copy in `mods` is skipped with a warning. A different JAR with the same ID in `mods` stops startup.

### Disable a plugin

`Mods` entries in Hytale's `config.json` are keyed by `Group:Name`. Setting a plugin's `Enabled` to `false` skips its Mixins, not its classes or native transformers. A plugin whose manifest sets `DisabledByDefault` is skipped the same way unless its `Enabled` is `true`. To disable a plugin completely, remove its JAR from all scanned directories.

Enabling a skipped plugin while the server runs applies its Mixins only after a restart. Unloading a plugin does not remove applied Mixins.

### Check Mixins after an update

A Mixin can fail long after startup, when its target class first loads. Run Hyinit once with `--check-mixins` after updating Hytale, Hyinit or a plugin. Use the server's working directory, the path to your Hyinit JAR and any `--server-jar` and `--early-plugins` options:

```shell
java -jar earlyplugins/Hyinit-X.X.X.jar --check-mixins
```

It loads every target class and exits with status 1 if a class fails to load, an injector matches nothing, even one without `require`, or a Mixin of a config without `"required": true` could not be applied. Mixins and handlers that a [condition](/mod/hyinit/conditions-and-manifest-reference#conditions) skips are not checked.

It also warns when Mixins from two JARs conflict on one method and logs one warning per JAR whose [fingerprinted](/mod/hyinit/conditions-and-manifest-reference#changed-hytale-methods) methods or fields changed. `--report` lists each one. A normal start reads fingerprints only for JARs that ask for it in their manifest.

When a required Mixin config cannot be prepared, for example because a target class is missing, the check logs one error per config that names the JAR to update or remove. It goes on with the other configs, so one run lists every such JAR. `--report` lists them under `check.notPrepared`. A normal start stops at the first one.

A Mixin of a config without `"required": true` that cannot be prepared or applied is dropped by Mixin, which logs a warning with the reason. The check counts each such Mixin as a failure, ends with `<count> Mixin(s) of optional configs could not be applied and were skipped: <mixin>, <mixin>. The warnings above show why.` and lists them under `check.notApplied`. A normal start goes on without them.

Before its last line the check logs one line per early-plugin JAR it found, such as `mymod.jar: 84 of 84 targets loaded, 93 Mixins skipped by conditions, 61 Mixins off by its settings, fingerprints compared`, `lenient.jar: 12 of 12 targets loaded, 2 Mixins not applied, see the warnings above`, `other.jar: no Mixins` or `broken.jar: not prepared, see its error`. Disabled plugins and skipped JARs are listed too. `--report` has the same data under `check.jars`, and the class lists stay in the other `check` keys. A normal start does not log these lines.

The check does not:

- Run static initialisers or native transformers.
- Detect injectors that declare `expect = 0`.

## Launcher reference

### Options

| Option | Default | Meaning |
| --- | --- | --- |
| `--server-jar=<path>` | Automatic discovery | Use this server JAR instead of searching. |
| `--early-plugins=<dir1,dir2>` | Default directories below | Add early-plugin directories in argument order. |
| `-Dhyinit.failOnClassConflicts=<boolean>` | `false` | Fail on conflicting contents for the same class name. Otherwise warn and identify both JARs. |
| `-Dhyinit.debugClassLoader` | Off | Log every class the Hyinit class loader cannot find and every Mixin synthetic class it generates or fails to generate. Any value turns it on. |
| `-Dmixin.debug.countInjections=false` | `true` with `--check-mixins` | Makes `--check-mixins` skip the injector check. |
| `--check-mixins` | Off | [Check Mixins](#check-mixins-after-an-update) and exit without starting the server. |
| `--strict` | Off | Make `--check-mixins` also exit with status 1 on Mixin conflict and fingerprint warnings. |
| `--report=<file>` | Off | Write the `--check-mixins` result, its warnings, the per-JAR summary, every changed method and field and the diagnostics as JSON. |
| `--write-fingerprints=<jar>` | Off | Write the [fingerprints](/mod/hyinit/conditions-and-manifest-reference#changed-hytale-methods) of the Hytale methods the JAR's Mixins use into the JAR and exit. |
| `--fingerprint-server=[<version>=]<server jar>` | `--server-jar` | Server JAR to record a `--write-fingerprints` baseline for. Repeat it for one baseline per Hytale version. The version defaults to the JAR's `Implementation-Version`. |
| `--version` | Off | Print the Hyinit version and exit. Only with `-jar`. |

`-D` options go before `-jar`. The others go after the Hyinit JAR path. `--strict` and `--report` are ignored with a warning without `--check-mixins`.

`--server-jar`, `--early-plugins`, `--report`, `--write-fingerprints` and `--fingerprint-server` also accept a space before their value. `--serverJar` is an alias for `--server-jar`.

Other arguments pass through to Hytale.

### JAR discovery

Server discovery checks the working directory, then Hyinit's directory. In each directory, `HytaleServer.jar` is used if it is a server JAR. Otherwise a single server JAR under any name is used. More than one stops startup.

Early-plugin directories are scanned in this order:

1. `earlyplugins` in the working directory.
2. `earlyplugins` beside Hyinit.
3. Directories added through `--early-plugins`.

JARs are sorted by filename within each directory. Paths and symlinks to the same real JAR are scanned once.

Hyinit loads server and early-plugin classes in one class loader. Class lookup prefers the server JAR, then Hyinit, then early-plugin scan order. Native transformers run on server classes before Mixins apply.

Mixin configs are read from all early-plugin JARs sorted by full real path. That order decides which identical copy is kept, which same-named config keeps its name and the order in which configs are registered.
