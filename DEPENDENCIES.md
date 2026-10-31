# Dependencies

Versions live in `gradle/libs.versions.toml`. Verification status:

- **Verified** = read from a source inspected in the authoring session.
- **Unverified** = chosen but never resolved (Maven/Google repos were blocked in the sandbox). The first CI run is the proof.

| Dependency | Version | Why | Status |
|---|---|---|---|
| Android Gradle Plugin | 8.13.0 | Build | **Unverified** (AGP 9.0.1 is what Media3 uses, but 9.x changes Kotlin integration; 8.x is the conservative choice) |
| Gradle wrapper | 8.14.3 (`-bin`) | Build | Wrapper *jar* copied from Media3 (Gradle 9.1 wrapper); Gradle 8.14.3 exists locally in the sandbox |
| Kotlin | 2.2.0 | Language; compiler 2.2.0 was downloaded and used to compile and test the pure-Kotlin modules | Verified |
| KSP | 2.2.0-2.0.2 | Room annotation processing | **Unverified**: must match the Kotlin version |
| kotlinx.serialization-json | 1.9.0 | Project file format | **Unverified**; `core:project` codec tests could not run locally |
| kotlinx.coroutines | 1.9.0 | Room Flow / UI | Verified (Media3 catalog) |
| Compose BOM | 2026.05.00 | UI | Verified (Media3 catalog) |
| Media3 | 1.11.1 | Export, GL effects | Verified (RELEASENOTES.md in the clone) |
| Room | 2.8.5 | Database (min SDK 23, Kotlin >= 2.0) | Verified (official release page) |
| Robolectric / JUnit / Truth | 4.16 / 4.13.2 / 1.4.0 | Tests | Verified (Media3 catalog) |
| NDK / CMake | CMake 3.22.1 (NDK chosen by AGP) | JNI | **Unverified** |

SDK levels: `minSdk 23`, `compileSdk 36`, `targetSdk 36` (Media3 1.11.1 and Room 2.8 both require minSdk 23).

Deliberately **not** included: FFmpegKit (retired), Oboe (deferred), Lottie (deferred), Hilt/Koin (a 60-line container is enough for Phase 1), android-gpuimage-plus (overlaps Media3 effects).
