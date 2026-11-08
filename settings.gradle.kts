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
        // Add JitPack for any dependencies that might need it
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "IngtiMixer"

include(":app")
