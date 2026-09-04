pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "candela"

// Pure Kotlin/JVM — no Android SDK required. These build and test anywhere.
include(":core-protocol")
include(":core-vision")

// Android modules. Uncommented as each stage lands (see ANDROID_NATIVE_PLAN.md).
// include(":optical-camera")
// include(":optical-render")
// include(":platform")
// include(":app")
