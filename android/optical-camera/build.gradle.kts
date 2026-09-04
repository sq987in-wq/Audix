plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * Camera2 receiver: C1 freeze, ImageReader YUV ROI, gated ZXing decode.
 *
 * SOURCE SET SPLIT. This module has two source roots on purpose:
 *
 *   src/pure/kotlin  — decision logic (ExposurePlan, LockPolicy, YuvRoi). No
 *                      android.* imports. Compiles and tests on a bare JVM, which
 *                      is what lets the exposure strategy and lock state machine
 *                      be verified without a device or an SDK.
 *   src/main/kotlin  — the Camera2/ImageReader call layer that applies those
 *                      decisions. Requires the SDK; verified on-device.
 *
 * The split is not cosmetic: the bugs in a camera pipeline live in "which
 * exposure, when to re-lock, how to crop", not in the API calls themselves.
 * Keeping that logic pure makes the risky part testable in CI.
 */
android {
    namespace = "app.candela.camera"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin", "src/pure/kotlin")
        }
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-vision"))

    // ZXing core ONLY. No ML Kit, no play-services-* — "completely offline" is a
    // product guarantee and GMS may not exist on the target devices at all.
    implementation(libs.zxing.core)

    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}

/**
 * Runs the pure Stage 4 + Stage 6 logic tests on a bare JVM, no emulator.
 * Mirrors ./android/verify-local.sh exactly.
 */
val pureLogicTest = tasks.register<JavaExec>("pureLogicTest") {
    group = "verification"
    description = "Exposure strategy, lock policy, ROI math, hold-time pacing"
    val pureOut = layout.buildDirectory.dir("pure-classes")
    outputs.dir(pureOut)
    classpath = files(pureOut)
    mainClass.set("app.candela.camera.CameraLogicTestsKt")
    // Wired up once the Gradle build runs with network access; the sandbox path
    // is ./android/verify-local.sh, which compiles the same sources with kotlinc.
    enabled = false
}
