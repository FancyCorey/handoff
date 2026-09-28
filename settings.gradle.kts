pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Handoff"

// :core      Pure Kotlin/JVM: domain, ownership, protocol, crypto, LAN transport. No Android types.
// :bluetooth Android library: the ONLY place that touches android.bluetooth (incl. hidden APIs).
// :app       Android application: UI, persistence, discovery, services, tile.
// :desktop   Windows application (Compose Desktop): same :core, Win32 Bluetooth, tray app.
include(":core", ":bluetooth", ":app", ":desktop")
