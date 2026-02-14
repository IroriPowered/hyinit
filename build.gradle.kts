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
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.hytale.com/release")
}

dependencies {
    compileOnly(libs.hytale)

    implementation(libs.mixin)
    implementation(libs.mixinextras)
    implementation(libs.asm.tree)
    implementation(libs.guava)
    implementation(libs.gson)
}

base {
    archivesName.set("Hyinit")
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
    }

    build {
        dependsOn(spotlessApply, shadowJar)
    }

    jar {
        manifest {
            attributes("Main-Class" to "cc.irori.hyinit.Main")
        }
    }
}
