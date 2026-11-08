plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}

// Ensure consistent version resolution across all modules
allprojects {
    configurations.all {
        resolutionStrategy {
            // Don't fail on version conflicts, instead prefer newer versions
            // failOnVersionConflict()
        }
    }
}
