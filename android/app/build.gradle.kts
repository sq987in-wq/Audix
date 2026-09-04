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

    packaging {
        resources {
            // Licence/metadata collisions only. NOT a duplicate-class workaround:
            // this block cannot and must not influence classpath resolution.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}

dependencies {
    implementation(project(":core-protocol"))
    implementation(project(":core-vision"))
    implementation(project(":optical-camera"))
    implementation(project(":optical-render"))
    implementation(project(":platform"))

    // BOM first so the Compose artifacts below resolve without explicit versions.
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)

    // Required by ReceiveViewModel (AndroidViewModel, viewModelScope). This was
    // previously absent and only worked by accident as a transitive of
    // activity-compose — an implicit edge that would break on any BOM bump.
    implementation(libs.androidx.lifecycle.viewmodel)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
