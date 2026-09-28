import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "jadx.plugins.emu"
version = System.getenv("VERSION") ?: "dev"

repositories {
    mavenCentral()
    google()
}

dependencies {
    val jadxVersion = "1.5.6"

    // provided by jadx at runtime, excluded from the plugin jar
    compileOnly("io.github.skylot:jadx-core:$jadxVersion")
    compileOnly("io.github.skylot:jadx-dex-input:$jadxVersion")
    compileOnly("io.github.skylot:jadx-java-input:$jadxVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation("io.github.skylot:jadx-core:$jadxVersion")
    testImplementation("io.github.skylot:jadx-dex-input:$jadxVersion")
    testImplementation("io.github.skylot:jadx-java-input:$jadxVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveClassifier = ""
        mergeServiceFiles()
    }

    register<Copy>("dist") {
        group = "jadx-plugin"
        dependsOn(shadowJar)
        from(shadowJar)
        into(layout.buildDirectory.dir("dist"))
    }
}
