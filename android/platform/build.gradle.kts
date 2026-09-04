plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * Android platform services: MediaStore export.
 *
 * Deliberately thin. Everything that DECIDES whether a file may be written lives
 * in :core-protocol (ExportGate) and is verified on a bare JVM; this module only
 * performs the write, using the staging protocol that makes a partial file
 * impossible to observe.
 */
android {
    namespace = "app.candela.platform"
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
        getByName("main") { kotlin.srcDirs("src/main/kotlin") }
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
