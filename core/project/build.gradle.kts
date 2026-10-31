plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
