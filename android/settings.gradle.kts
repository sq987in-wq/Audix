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
// Stage 4 (Camera2 receiver) and Stage 6 (SurfaceView sender) are implemented.
// Their pure decision logic is verified on a bare JVM by ./android/verify-local.sh;
// the Android call layers build in CI, which has the SDK.
include(":optical-camera")
include(":optical-render")
// include(":platform")
// include(":app")
