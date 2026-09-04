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
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

configurations.all {
    resolutionStrategy {
        preferProjectModules()
        // Duplicate class conflict fix: align lifecycle and savedstate versions
        force("androidx.savedstate:savedstate:1.2.1")
        force("androidx.savedstate:savedstate-ktx:1.2.1")
        force("androidx.lifecycle:lifecycle-viewmodel:2.8.6")
        force("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
        force("androidx.lifecycle:lifecycle-runtime:2.8.6")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-vision"))
    implementation(project(":optical-camera"))
    implementation(project(":optical-render"))
    implementation(project(":platform"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
