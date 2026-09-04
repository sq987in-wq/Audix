plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

/**
 * Pure Kotlin/JVM. ZERO Android imports, zero third-party dependencies.
 *
 * This is deliberate and load-bearing:
 *  - It compiles and tests with nothing but a JDK, which is what makes the whole
 *    protocol layer verifiable before any Android SDK exists.
 *  - Ed25519 is implemented in-module (see Ed25519.kt) rather than pulled from
 *    java.security, because Android only guarantees EdDSA from API 33 and minSdk
 *    is 26. No BouncyCastle, no Tink, no Play Services.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

application {
    mainClass.set("app.candela.protocol.GoldenTestsKt")
}

sourceSets {
    main { kotlin.srcDirs("src/main/kotlin") }
    test { kotlin.srcDirs("src/test/kotlin"); resources.srcDirs("src/test/resources") }
}

/**
 * The golden-vector runner. Fails the build on any byte-level divergence from the
 * frozen TypeScript reference in /src.
 */
val goldenTest = tasks.register<JavaExec>("goldenTest") {
    group = "verification"
    description = "Assert Kotlin protocol is byte-identical to the TypeScript reference"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.candela.protocol.GoldenTestsKt")
    args = listOf(file("src/test/resources/golden").absolutePath)
}

tasks.named("test") { dependsOn(goldenTest) }

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
