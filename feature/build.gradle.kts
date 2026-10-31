plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.motionlab.feature"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:project"))
    api(project(":core:database"))
    api(project(":core:task"))
    api(project(":core:resource"))
    api(project(":engine:timeline"))
    api(project(":engine:media"))
    api(project(":engine:rendering"))
    api(project(":native:cpp-core"))

    implementation(libs.kotlinx.coroutines.android)

    val bom = platform(libs.androidx.compose.bom)
    implementation(bom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
