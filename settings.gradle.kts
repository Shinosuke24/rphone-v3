pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "R-Phone V3"

// Multi-platform modules
include(":core")           // Shared business logic (platform-independent)
include(":app")            // Android APK
include(":desktop")        // Windows Desktop (EXE)
