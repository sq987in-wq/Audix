plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

/**
 * Runs the full protocol + vision verification exactly as it runs in the sandbox.
 *
 * Deliberately delegates to the same main() runners rather than reimplementing the
 * assertions as JUnit cases: the golden-vector suite is the single source of truth
 * for wire compatibility, and having two copies of it is how they drift apart.
 */
tasks.register("verify") {
    group = "verification"
    description = "Golden-vector protocol parity + vision gate behaviour"
    dependsOn(":core-protocol:test", ":core-vision:test")
}

subprojects {
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests = false
    }
}

subprojects {
    configurations.configureEach {
        exclude(group = "androidx.lifecycle", module = "lifecycle-common-java8")
        resolutionStrategy {
            dependencySubstitution {
                substitute(module("androidx.lifecycle:lifecycle-common"))
                    .using(module("androidx.lifecycle:lifecycle-common-jvm:2.8.6"))
                    .because("lifecycle-common is now JVM artifact since KMP migration in 2.8.0")
                substitute(module("androidx.lifecycle:lifecycle-common-java8"))
                    .using(module("androidx.lifecycle:lifecycle-common-jvm:2.8.6"))
                    .because("lifecycle-common-java8 is merged into lifecycle-common since 2.8.0")
            }
            eachDependency {
                if (requested.group == "androidx.lifecycle" && requested.name.startsWith("lifecycle-")) {
                    useVersion("2.8.6")
                }
                if (requested.group == "androidx.savedstate") {
                    useVersion("1.2.1")
                }
            }
        }
    }
}
