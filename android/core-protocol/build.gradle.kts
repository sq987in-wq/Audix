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

// The golden vectors are copied into the test runtime; two source roots can
// legitimately contribute the same resource path. Keep the first.
tasks.withType<Copy>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// The real assertions run via the goldenTest JavaExec below, not JUnit, so the
// `test` task legitimately discovers zero test classes. Gradle 9 fails the build
// on that by default; Gradle 8 has no such property and errors on the setter, so
// it is set reflectively and skipped when absent.
tasks.withType<Test>().configureEach {
    runCatching {
        javaClass.getMethod("setFailOnNoDiscoveredTests", Boolean::class.java)
            .invoke(this, false)
    }
}
