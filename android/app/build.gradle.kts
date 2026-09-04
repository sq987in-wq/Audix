plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.candela.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.candela"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    buildFeatures {
        compose = true
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

    buildTypes {
        release {
            // Off for the alpha: a symbolicated crash from a field test is worth
            // more right now than a smaller APK.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-vision"))
    implementation(project(":optical-camera"))
    implementation(project(":optical-render"))
    implementation(project(":platform"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
