plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "id.motionlab.engine.effects"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    api(project(":core:common"))
    api(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
