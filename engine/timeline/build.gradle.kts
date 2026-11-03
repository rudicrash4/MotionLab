plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:common"))
    api(project(":engine:animation"))
    api(project(":core:project"))
    testImplementation(libs.junit)
}
