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
    }
}

rootProject.name = "IngtiMixer"

include(":app")
