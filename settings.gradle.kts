pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MotionLab"

include(
    ":app",
    ":core:common",
    ":core:project",
    ":core:resource",
    ":core:task",
    ":core:database",
    ":engine:timeline",
    ":engine:animation",
    ":engine:audio",
    ":engine:compositing",
    ":engine:text",
    ":engine:effects",
    ":engine:media",
    ":engine:export",
    ":engine:rendering",
    ":native:cpp-core",
    ":feature",
)
