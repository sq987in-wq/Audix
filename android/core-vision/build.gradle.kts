plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Pure Kotlin/JVM gate maths. No Android imports.
 *
 * Gates operate on a raw luma ByteArray + rowStride, which on-device is plane 0 of
 * an ImageReader YUV_420_888 Image with no conversion and no allocation, and
 * off-device is plain testable data. The Camera2/SensorManager plumbing that feeds
 * this lives in :optical-camera, so the arithmetic stays verifiable in CI.
 */
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

sourceSets {
    main { kotlin.srcDirs("src/main/kotlin") }
    test { kotlin.srcDirs("src/test/kotlin") }
}

val visionTest = tasks.register<JavaExec>("visionTest") {
    group = "verification"
    description = "Gate behaviour: admits sharp, blocks blur/low-contrast, refuses hard floors"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.candela.vision.VisionTestsKt")
}

tasks.named("test") { dependsOn(visionTest) }
