# Launching

[Home](Home.md) · [manifest.json](Manifest.md)

## Run the server

Put `HytaleServer.jar` beside `Hyinit-0.3.0.jar` and Mixin JARs in `earlyplugins`. Run with Java 25:

```shell
java -jar Hyinit-0.3.0.jar --assets=../Assets.zip
```

Hyinit has no configuration file. Put JVM options before `-jar`. Put launcher options and Hytale arguments after the Hyinit JAR path.

## Launcher options

| Option | Default | Meaning |
| --- | --- | --- |
| `--server-jar=<path>` | Automatic discovery | Use this server JAR instead of searching. |
| `--early-plugins=<dir1,dir2>` | Default directories below | Add early-plugin directories in argument order. |
| `-Dhyinit.failOnClassConflicts=<boolean>` | `false` | Fail on conflicting contents for the same class name. Otherwise warn and identify both JARs. |
| `--check-mixins` | Off | Load every class that a Mixin targets, report the ones that fail and exit without starting the server. |
| `--version` | Off | Print the Hyinit version and exit. |

`--server-jar` and `--early-plugins` also accept a space before their value. `--serverJar` is an alias for `--server-jar`.

Other arguments pass through to Hytale. Hyinit also forwards early-plugin directories without repeating Hytale's default scan.

## JAR discovery

Server discovery checks the working directory, then Hyinit's directory. In each directory, it checks `HytaleServer.jar` first, then other server JARs in filename order.

Early-plugin directories are scanned in this order:

1. `earlyplugins` in the working directory.
2. `earlyplugins` beside Hyinit.
3. Directories added through `--early-plugins`.

Only existing directories are scanned. JARs are sorted by filename within each directory. Paths and symlinks to the same real JAR are scanned once.

Class lookup prefers the server JAR, then Hyinit, then early-plugin scan order.

Mixin configs are read in a different order: all early-plugin JARs sorted by full real path. That order decides which identical copy is kept, which same-named config keeps its name and the order in which configs are registered.

## Duplicate and disabled plugins

Byte-identical copies of a JAR with the same `Group:Name` ID are loaded once, with a warning. JARs without an ID are not compared. Different JARs with the same `Group:Name` ID stop startup, even if their versions match. This also applies to disabled plugins.

`Mods` entries in Hytale's `config.json` are keyed by `Group:Name`. Setting a plugin's `Enabled` to `false` skips its Mixins, not its classes or native transformers. A plugin whose manifest sets `DisabledByDefault` is skipped the same way unless its `Enabled` is `true`. Remove its JAR from all scanned directories to disable it completely.

Mixins are chosen at launch. Enabling a skipped plugin while the server runs does not apply its Mixins until the next start. Hyinit logs a warning when that happens. Unloading a plugin does not remove Mixins that were already applied.

## Checking Mixins

A Mixin is applied when its target class is first loaded, which can be long after startup. `--check-mixins` loads all target classes at once and exits with status 1 if any of them fails to load. Run it after updating Hytale, Hyinit or a plugin.

The check has limits:

- It does not run static initialisers or native transformers.
- It does not detect injectors that are allowed to match nothing.
- It does not detect a failing Mixin in a config without `"required": true`. Mixin only logs those.
- A target class that is not found is logged as a warning.

## Crash reporting

Hyinit turns off Hytale's built-in crash reporting, as Hytale does when native early plugins are loaded. Set `SentryDsn` in `config.json` to send reports to your own Sentry project.
