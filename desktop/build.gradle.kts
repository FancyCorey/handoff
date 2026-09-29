import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

val appVersion = "0.4.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna.platform)
    implementation(libs.jmdns)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "dev.handoff.desktop.MainKt"
        jvmArgs += listOf("-Dhandoff.version=$appVersion")
        // `./gradlew :desktop:run -Phandoff.dataDir=...` keeps development data out of %APPDATA%.
        (project.findProperty("handoff.dataDir") as String?)?.let { jvmArgs += "-Dhandoff.dataDir=$it" }
        // Screenshots: `-Phandoff.demo=true -Phandoff.demo.name="Studio PC"` uses made-up headsets and names.
        listOf("handoff.demo", "handoff.demo.name", "handoff.demo.connected", "handoff.port").forEach { key ->
            (project.findProperty(key) as String?)?.let { jvmArgs += "-D$key=$it" }
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Handoff"
            packageVersion = appVersion
            description = "Move your Bluetooth headphones between your devices"
            vendor = "Handoff contributors"
            copyright = "MIT License"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "jdk.crypto.ec")
            windows {
                iconFile.set(project.file("src/main/resources/handoff.ico"))
                menuGroup = "Handoff"
                shortcut = true
                perUserInstall = true
                // Stable id so future installers upgrade in place.
                upgradeUuid = "6f7d5b8e-3c1a-4e7f-9b2d-5a6c8e1f0a42"
            }
        }
    }
}
