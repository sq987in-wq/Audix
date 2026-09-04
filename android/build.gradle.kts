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

/**
 * DEPENDENCY RESOLUTION — deliberately minimal. Read this before adding to it.
 *
 * AndroidX Lifecycle 2.8.x is a Kotlin Multiplatform build. `lifecycle-common` is
 * no longer a jar; it is a *variant-aware alias* that Gradle resolves to exactly
 * one real artifact based on the consumer's attributes:
 *
 *     lifecycle-common-android  <- for an Android consumer
 *     lifecycle-common-jvm      <- for a plain JVM/desktop consumer
 *
 * Gradle picks the right one automatically when the consuming module sets the
 * `org.jetbrains.kotlin.platform.type = androidJvm` attribute, which the
 * `kotlin-android` plugin does. Every Android module here (:app, :platform,
 * :optical-camera, :optical-render) already applies `kotlin-android`, so this
 * resolves correctly with NO intervention.
 *
 * This is why the previous `dependencySubstitution` block CAUSED the duplicate
 * rather than fixing it. Forcing `lifecycle-common` -> `lifecycle-common-jvm`
 * pins one edge of the graph to the JVM artifact while variant resolution still
 * pulls `lifecycle-common-android` for the rest, so both land on the runtime
 * classpath carrying the same `androidx.lifecycle.*` class files. That is the
 * literal definition of the error being chased. Substitution here is not a
 * band-aid over the problem; it is the problem.
 *
 * A single `constraints` block replaces the substitution, the version forcing and
 * the exclusions. Constraints align versions WITHOUT overriding variant
 * selection, which is precisely the distinction that matters for a KMP library.
 */
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            // Align the whole Lifecycle graph on one version. `prefer` (not
            // `strictly`/`force`) leaves variant-aware resolution intact, so
            // each consumer still gets its correct -android or -jvm artifact.
            eachDependency {
                if (requested.group == "androidx.lifecycle") {
                    useVersion(libs.versions.lifecycle.get())
                    because("one Lifecycle version across the graph; variant choice left to Gradle")
                }
            }
        }
    }

    dependencies {
        constraints {
            // lifecycle-common-java8 was merged into lifecycle-common in 2.8.0 and
            // now publishes an empty marker pointing at lifecycle-common. Anything
            // still requesting it transitively is upgraded rather than excluded:
            // excluding it would strip the marker and let an older, REAL 2.6.x jar
            // win, which reintroduces the duplicate from the other direction.
            add("implementation", "androidx.lifecycle:lifecycle-common-java8:${libs.versions.lifecycle.get()}") {
                because("merged into lifecycle-common in 2.8.0; upgrade, never exclude")
            }
        }
    }
}
