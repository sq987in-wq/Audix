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
