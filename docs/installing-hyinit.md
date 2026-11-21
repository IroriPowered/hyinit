# Installing Hyinit

Hyinit needs Java 25. It has no configuration file. Every way below is supported and gives mods the same Mixin setup.

## Install as an early plugin

Put the Hyinit JAR in an early-plugin directory beside the Mixin JARs and start the server as usual.

In single-player that directory is `UserData/EarlyPlugins` in the Hytale data folder.

The differences from starting Hyinit directly:

- A dedicated server needs `--accept-early-plugins` or a confirmation on the console. Single-player needs neither.
- Hytale constructs every native class transformer before Hyinit starts, including those in JARs Hyinit skips. Hyinit constructs the ones it keeps again. Remove JARs that Hyinit skips.
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

Byte-identical copies of a JAR with the same `Group:Name` ID are loaded once, with a warning. JARs without an ID are not compared. Different JARs with the same `Group:Name` ID stop startup, even if their versions match. This also applies to disabled plugins.

A JAR in `earlyplugins` with a `Main` is also loaded as a native plugin. An identical copy in `mods` is skipped with a warning. A different JAR with the same ID in `mods` stops startup.

### Disable a plugin

`Mods` entries in Hytale's `config.json` are keyed by `Group:Name`. Setting a plugin's `Enabled` to `false` skips its Mixins, not its classes or native transformers. A plugin whose manifest sets `DisabledByDefault` is skipped the same way unless its `Enabled` is `true`. Remove its JAR from all scanned directories to disable it completely.

Enabling a skipped plugin while the server runs applies its Mixins only after a restart. Unloading a plugin does not remove Mixins that were already applied.

### Check Mixins after an update

A Mixin can fail long after startup, when its target class first loads. Run Hyinit once with `--check-mixins` after updating Hytale, Hyinit or a plugin. Use the server's working directory and keep any `--server-jar` and `--early-plugins` options:

```shell
java -jar earlyplugins/Hyinit-X.X.X.jar --check-mixins
```

Use the path to your Hyinit JAR if it is not in `earlyplugins`.

It loads every target class and exits with status 1 if a class fails to load or an injector matches nothing, even one without `require`. Add `-Dmixin.debug.countInjections=false` before `-jar` to skip the injector check.

The check does not:

- Run static initialisers or native transformers.
- Detect injectors that declare `expect = 0`.
- Detect a failing Mixin in a config without `"required": true`. Mixin only logs those.

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
| `--version` | Off | Print the Hyinit version and exit. Only with `-jar`. |

`-D` options go before `-jar`. The others go after the Hyinit JAR path.

`--server-jar` and `--early-plugins` also accept a space before their value. `--serverJar` is an alias for `--server-jar`.

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
