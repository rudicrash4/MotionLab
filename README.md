# MotionLab 2.0 - Phase 1 (foundation)

Modular Android video/motion-graphics editor foundation: Kotlin + Jetpack Compose, Media3 for export and GL effects,
Room, C++/JNI, OpenGL ES rendering. See `INTEGRATION_REPORT.md` for which open-source projects were evaluated and why,
`ARCHITECTURE.md` for the module map and an honest status table, `BUILD_INSTRUCTIONS.md` to build,
`DEPENDENCIES.md` and `OPEN_SOURCE_LICENSES.md` for versions and licenses.

**Read first:** this phase was authored in a sandbox with no Android SDK and no access to Maven/Google repositories.
The pure-Kotlin modules and the C++ code were compiled and tested there; the Android modules were written but have
never been built by Gradle. Run the GitHub Actions workflow and send back any build log; expect a first round of fixes.
