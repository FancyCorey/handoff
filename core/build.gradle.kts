plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

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
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Only for the opt-in DevicePeerE2ETest (decodes the pairing QR from a screenshot).
    testImplementation(libs.zxing.core)
}

tasks.test {
    listOf("handoff.e2e.qr", "handoff.e2e.out", "handoff.e2e.host").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    outputs.upToDateWhen { System.getProperty("handoff.e2e.qr") == null }
}

// Maintainer release tool (keygen / sign). Arguments separated by "," (cmd treats "|" as a pipe):
// ./gradlew :core:releaseTool -PtoolArgs="sign,<dir>,<version>,<notes>,<key>"
tasks.register<JavaExec>("releaseTool") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.handoff.core.update.ReleaseTool")
    args = (project.findProperty("toolArgs") as String?)?.split(",") ?: emptyList()
}
