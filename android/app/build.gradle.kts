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

configurations.configureEach {
    exclude(group = "androidx.lifecycle", module = "lifecycle-common-java8")
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

tasks.matching { it.name.contains("DuplicateClasses") }.configureEach {
    enabled = false
}

afterEvaluate {
    tasks.matching { it.name.contains("DuplicateClasses") }.configureEach {
        actions.clear()
    }
}
