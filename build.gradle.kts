plugins {
    java
    alias(libs.plugins.spotless)
    alias(libs.plugins.shadow)
}

/* Project Properties */
val projectGroup    = project.property("project_group")     as String
val projectVersion  = project.property("project_version")   as String

group = projectGroup
version = projectVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

val api: SourceSet = sourceSets.create("api")

spotless {
    java {
        palantirJavaFormat()
    }
}

repositories {
    mavenCentral()
    maven("https://maven.hytale.com/release")
    maven("https://maven.hytale.com/pre-release")
}

val localHytaleServerJar = (findProperty("hytaleServerJar") as String?)?.trim().orEmpty()

dependencies {
    if (localHytaleServerJar.isNotEmpty()) {
        val localJar = file(localHytaleServerJar)
        if (!localJar.isFile) {
            throw GradleException("hytaleServerJar does not exist: ${localJar.absolutePath}")
        }
        compileOnly(files(localJar))
        testCompileOnly(files(localJar))
        testRuntimeOnly(files(localJar))
    } else {
        compileOnly(libs.hytale)
        testCompileOnly(libs.hytale)
        testRuntimeOnly(libs.hytale)
    }

    implementation(api.output.classesDirs)
    implementation(libs.mixin)
    implementation(libs.mixinextras)
    implementation(libs.asm.tree)
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    constraints {
        implementation(libs.asm.analysis)
        implementation(libs.asm.commons)
        implementation(libs.asm.util)
    }
}

base {
    archivesName.set("Hyinit")
}

val hytaleServerJar: FileCollection = if (localHytaleServerJar.isNotEmpty()) {
    files(localHytaleServerJar)
} else {
    configurations.detachedConfiguration(project.dependencies.create(libs.hytale.get())).apply {
        isTransitive = false
    }
}

tasks {
    processResources {
        inputs.property("version", version)
        filteringCharset = "UTF-8"

        filesMatching("manifest.json") {
            expand(
                "version" to version
            )
        }
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    named<JavaCompile>(api.compileJavaTaskName) {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    compileTestJava {
        options.encoding = "UTF-8"
    }

    val hytaleParityTest = register<Test>("hytaleParityTest") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        filter {
            includeTestsMatching("cc.irori.hyinit.util.VersionRangeTest")
        }
    }

    val sharedJvmTests = listOf(
        "cc.irori.hyinit.CallCheckTest",
        "cc.irori.hyinit.CorePluginIdsTest",
        "cc.irori.hyinit.FingerprintsTest",
        "cc.irori.hyinit.RequirementsTest",
        "cc.irori.hyinit.SharedPackagesTest",
        "cc.irori.hyinit.mixin.MixinConflictCheckTest",
        "cc.irori.hyinit.mixin.OptionalPatchCheckTest")

    val isolatedTest = register<Test>("isolatedTest") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath.minus(hytaleServerJar)
        filter {
            excludeTestsMatching("cc.irori.hyinit.util.VersionRangeTest")
            sharedJvmTests.forEach(::excludeTestsMatching)
        }
        useJUnitPlatform()
        forkEvery = 1
        systemProperty("hyinit.projectVersion", projectVersion)
        val fabricMixin = libs.versions.mixin.get()
        systemProperty("hyinit.fabricMixinVersion", fabricMixin.substringBefore("+"))
        systemProperty("hyinit.mixinVersion", fabricMixin.substringAfter("+mixin."))
        systemProperty("hyinit.mixinExtrasVersion", libs.versions.mixinextras.get())
    }

    test {
        dependsOn(hytaleParityTest, isolatedTest)
        classpath = classpath.minus(hytaleServerJar)
        filter {
            sharedJvmTests.forEach(::includeTestsMatching)
        }
        useJUnitPlatform()
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.google.gson", "$projectGroup.libs.com.google.gson")
        exclude(
            "org/spongepowered/tools/**",
            "com/llamalad7/mixinextras/ap/**",
            "com/llamalad7/mixinextras/utils/MixinAPInternals*.class",
            "META-INF/services/javax.annotation.processing.Processor",
            "META-INF/services/org.spongepowered.tools.*",
            "META-INF/services/cpw.mods.*",
            "com/google/errorprone/**",
            "org/spongepowered/asm/service/mojang/**",
            "org/spongepowered/asm/service/modlauncher/**",
            "org/spongepowered/asm/launch/platform/MixinPlatformAgentMinecraftForge*.class",
            "org/spongepowered/asm/launch/platform/MixinPlatformAgentFMLLegacy*.class",
            "org/spongepowered/asm/launch/platform/MixinPlatformAgentLiteLoaderLegacy*.class",
            "org/spongepowered/asm/launch/platform/container/ContainerHandleModLauncher*.class",
            "META-INF/maven/**",
            "META-INF/proguard/**",
            "fabric.mod.json")
        minimize {
            exclude(dependency("net.fabricmc:.*:.*"))
            exclude(dependency("io.github.llamalad7:.*:.*"))
            exclude(dependency("org.ow2.asm:.*:.*"))
            exclude(dependency("com.google.errorprone:.*:.*"))
        }
        from(listOf("LICENSE", "NOTICE")) {
            into("META-INF/hyinit")
        }
        from("licenses") {
            into("META-INF/hyinit/licenses")
        }
    }

    register<Jar>("apiJar") {
        archiveBaseName.set("hyinit-api")
        from(api.output)
        from("LICENSE") {
            into("META-INF")
        }
    }

    build {
        dependsOn(spotlessCheck, shadowJar, "apiJar")
    }

    jar {
        manifest {
            attributes("Main-Class" to "cc.irori.hyinit.Main", "Enable-Native-Access" to "ALL-UNNAMED")
        }
    }

    register("printHytaleServerJar") {
        inputs.files(hytaleServerJar)
        doLast {
            println(hytaleServerJar.singleFile.absolutePath)
        }
    }
}
