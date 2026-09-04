plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

subprojects {
    configurations.configureEach {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "androidx.lifecycle") {
                    useVersion("2.8.6")
                }
                if (requested.group == "androidx.savedstate") {
                    useVersion("1.2.1")
                }
            }
        }
    }
}
