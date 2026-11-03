# Build instructions

## Requirements
JDK 17, Android SDK (platform 36), NDK + CMake 3.22.1 (install via Android Studio SDK Manager). Internet access to Google/Maven Central.

## Build and test
```
./gradlew test                 # JVM + Robolectric unit tests, all modules
./gradlew :app:assembleDebug   # APK: app/build/outputs/apk/debug/
./gradlew :native:cpp-core:connectedDebugAndroidTest :engine:rendering:connectedDebugAndroidTest   # needs a device/emulator
```

## Build from a phone (GitHub Actions)
Push the project to a GitHub repository. `.github/workflows/build.yml` runs unit tests, builds the debug APK and uploads
it as the artifact `MotionLab-debug-apk`. The instrumented job (emulator) is allowed to fail without blocking the others.
Run the workflow manually with `with_ffmpeg = true` to build the optional LGPL FFmpeg and an APK that bundles it.

## Optional FFmpeg
`ANDROID_NDK_HOME=/path/to/ndk scripts/build_ffmpeg_android.sh` (Linux x86_64 host). See OPEN_SOURCE_LICENSES.md for obligations.
Without it the app builds and reports "FFmpeg not bundled".

## If the first build fails
The project was written without access to the Android SDK or Maven, so the Android modules have never been compiled by
Gradle. Most likely first failures: (1) KSP version not matching Kotlin: pick the KSP release for Kotlin 2.2.0 from
https://github.com/google/ksp/releases; (2) AGP/Gradle mismatch: align per the AGP release notes; (3) a Media3 API difference.
Send the log and it will be fixed.
