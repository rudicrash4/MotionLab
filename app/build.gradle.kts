plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.motionlab.editor"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.motionlab.editor"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "2.0.0-phase1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            // Required so the FFmpeg .so files (if bundled) stay page-aligned for 16 KB devices.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":feature"))
    implementation(project(":engine:export"))
    implementation(project(":engine:effects"))
    implementation(project(":engine:text"))
    implementation(project(":engine:audio"))
    implementation(project(":engine:compositing"))

    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
