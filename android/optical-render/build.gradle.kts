plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * Sender render path: SurfaceView QR plane, fixed 60 Hz, zero-allocation blitting.
 *
 * Same pure/main source-set split as :optical-camera — the pacing arithmetic
 * (HoldTimePlan, SymbolScheduler) is where correctness lives and is JVM-testable;
 * the SurfaceView/Choreographer/Display layer that consumes it needs the SDK.
 */
android {
    namespace = "app.candela.render"
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

    // QRCodeWriter for pre-rasterizing symbols. Same ZXing-core-only rule.
    implementation(libs.zxing.core)

    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
