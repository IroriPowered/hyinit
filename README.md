# Hyinit

[Documentation](https://wiki.hytalemodding.dev/mod/hyinit)

## Releases

From 0.5.0 on, each [release](https://github.com/IroriPowered/hyinit/releases) has two files. `Hyinit-<version>.jar` is the launcher for servers. `hyinit-api-<version>.jar` holds only the condition annotations, for plugin builds that do not want the whole launcher on the compile classpath. `./gradlew build` writes both to `build/libs`.

## Licence

Hyinit is licensed under the [MIT License](LICENSE). Its class loader and Mixin service derive from Fabric Loader's Knot under the Apache License 2.0. See [NOTICE](NOTICE).
