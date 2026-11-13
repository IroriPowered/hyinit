# Changelog

[Home](Home.md)

## 0.3.0

Built against Hytale 0.7.7. The changes below are relative to 0.2.3.

### Upgrade notes

- Hyinit no longer provides Guava. A plugin that uses `com.google.common` must bundle and relocate it.
- Mixin is updated from 0.17.0 to 0.17.5 and MixinExtras from 0.5.3 to 0.5.5. Plugins now get Mixin 0.17.5 behaviour. Set `"HyinitMixinCompatibility": "0.17.0"` in `manifest.json` to keep the behaviour of Hyinit 0.2.x. The differences:
  - A required Mixin config whose `@Mixin` target class is missing stops startup. It used to log a warning.
  - Nested `->` target selectors are applied. They used to be ignored.
  - Field and static initialisers from Mixins are merged in Mixin's newer order.
- Different early-plugin JARs with the same `Group:Name` ID stop startup. Identical copies are loaded once.
- Different Mixin configs with the same file name and overlapping packages stop startup.
- A plugin that is disabled in `config.json` or by `DisabledByDefault` no longer has its Mixins applied.
- Date-style version ranges such as `2026.01.02-abcdef` are no longer read as `*`. Hytale reads them in `ServerVersion` itself. In `Dependencies` and `OptionalDependencies` they are now invalid.
- Hytale's built-in crash reporting is off under Hyinit. A custom `SentryDsn` still works.
- Other plugins that use the classes of a combined JAR now get the copies of its native plugin. They used to get the early copies, which broke casts to the plugin's main class.
- A failed launch now ends with exit status 1.

### Added

- Native early-plugin class transformers run under Hyinit. `--early-plugins` directories are passed on to Hytale.
- `HyinitSharedPackages` and `HyinitMixinCompatibility` in `manifest.json`.
- A warning when early-plugin JARs contain different versions of the same class, and `-Dhyinit.failOnClassConflicts=true` to stop startup instead.
- `--check-mixins` and `--version`.
- Combined plugins can be reloaded while the server runs.
- Launcher messages are also written to the server log file.
- Mixin configs with the same file name and separate packages are both applied.

### Fixed

- Unloading one plugin no longer closes the class loader that other plugins from the same JAR still use.
- A combined plugin gets a new class loader after a failed reload.
- A manifest that lists a Mixin config from another JAR no longer hides which JAR the config came from.
- Mixin errors logged through Hytale's logger keep their cause.
- Flogger classes are excluded from Mixin transformation as intended.
