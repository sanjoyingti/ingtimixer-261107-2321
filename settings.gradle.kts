pluginManagement {
    repositories {
        val proxy = System.getenv("INGTI_MAVEN_PROXY")
        if (proxy != null) {
            maven {
                url = uri("$proxy/google")
                isAllowInsecureProtocol = true
            }
            maven {
                url = uri("$proxy/central")
                isAllowInsecureProtocol = true
            }
            maven {
                url = uri("$proxy/plugins")
                isAllowInsecureProtocol = true
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val proxy = System.getenv("INGTI_MAVEN_PROXY")
        if (proxy != null) {
            maven {
                url = uri("$proxy/google")
                isAllowInsecureProtocol = true
            }
            maven {
                url = uri("$proxy/central")
                isAllowInsecureProtocol = true
            }
        } else {
            google()
            mavenCentral()
        }
        // Add JitPack for any dependencies that might need it
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "IngtiMixer"

include(":app")
