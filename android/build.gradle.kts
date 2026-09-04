plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * Runs the protocol + vision verification exactly as the sandbox does.
 *
 * Restored: it was dropped in 8ba9cae, but the CI `kotlin-verify` job invokes
 * `gradle verify` directly, so removing it fails that job with "task not found".
 *
 * Delegates to the same main() runners rather than reimplementing the assertions
 * as JUnit cases: the golden-vector suite is the single source of truth for wire
 * compatibility, and a second copy is how the two drift apart.
 */
tasks.register("verify") {
    group = "verification"
    description = "Golden-vector protocol parity + vision gate behaviour"
    dependsOn(":core-protocol:test", ":core-vision:test")
}

/**
 * Lifecycle 2.8.x is a Kotlin Multiplatform build: `lifecycle-common` is a
 * variant-aware alias that Gradle resolves to `-android` or `-jvm` from the
 * consumer's attributes. Every Android module here applies `kotlin-android`,
 * which sets platform.type=androidJvm, so resolution is already correct.
 *
 * Align versions ONLY. Do not reintroduce dependencySubstitution here: forcing
 * `lifecycle-common` to a concrete artifact overrides variant selection and puts
 * both the -android and -jvm jars on the runtime classpath, which is exactly the
 * duplicate-class failure this replaced.
 */
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
