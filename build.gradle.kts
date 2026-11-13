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
    } else {
        compileOnly(libs.hytale)
    }

    implementation(libs.mixin)
    implementation(libs.mixinextras)
    implementation(libs.asm.tree)
    implementation(libs.gson)

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
            "META-INF/maven/com.google.errorprone/**")
        from(listOf("LICENSE", "NOTICE")) {
            into("META-INF/hyinit")
        }
        from("licenses") {
            into("META-INF/hyinit/licenses")
        }
    }

    build {
        dependsOn(spotlessCheck, shadowJar)
    }

    jar {
        manifest {
            attributes("Main-Class" to "cc.irori.hyinit.Main")
        }
    }

    register("printHytaleServerJar") {
        inputs.files(hytaleServerJar)
        doLast {
            println(hytaleServerJar.singleFile.absolutePath)
        }
    }
}
