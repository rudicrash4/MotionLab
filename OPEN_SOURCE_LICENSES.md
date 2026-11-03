# Open-source licenses and attributions

MotionLab's own code is © its authors. Third-party components below are used under their licenses. This file
must ship with any distribution of the app. The full Apache 2.0 text is in `LICENSE-Apache-2.0.txt`.

| Component | Used how | License | Source | Notes |
|---|---|---|---|---|
| AndroidX Media3 1.11.1 (`media3-common`, `-effect`, `-transformer`) | Maven dependency (export pipeline, GL effects) | Apache 2.0 | https://github.com/androidx/media | LICENSE inspected in a clone |
| AndroidX Room 2.8.5 | Maven dependency (database) | Apache 2.0 | https://developer.android.com/jetpack/androidx/releases/room | |
| Jetpack Compose (BOM 2026.05.00), Activity, Lifecycle | Maven dependency (UI) | Apache 2.0 | https://developer.android.com/jetpack | |
| Kotlin 2.2.0, kotlinx.coroutines 1.9.0, kotlinx.serialization | Maven dependency | Apache 2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization | License from general knowledge; not re-verified in this session |
| JUnit 4.13.2 | Test only | EPL 1.0 | https://junit.org/junit4/ | Not shipped in the APK |
| Robolectric 4.16, Truth | Test only | Apache 2.0 | | Not shipped in the APK |
| **FFmpeg n8.1.3** (optional) | Built from source by `scripts/build_ffmpeg_android.sh` as **shared** libraries, only when you choose to run it | **LGPL 2.1 or later** (configured without `--enable-gpl` and `--enable-nonfree`) | https://github.com/FFmpeg/FFmpeg | See obligations below |
| google/grafika | **Reference only.** No code copied | Apache 2.0 | https://github.com/google/grafika | Consulted for the EGL/GLES structure |
| google/oboe | **Not used in Phase 1** | Apache 2.0 | https://github.com/google/oboe | Candidate for the Phase 2 audio engine |

## FFmpeg obligations (only if the optional FFmpeg build is distributed)
1. Ship FFmpeg's license text (LGPL 2.1) and state that the app uses FFmpeg.
2. FFmpeg is linked **dynamically** (`libavcodec.so`, `libavformat.so`, `libavutil.so`, `libswresample.so`); users must be able to replace these libraries. Do not switch to static linking without legal review.
3. Provide, or offer, the exact FFmpeg source and the configure line used (see the script) for the build you distribute.
4. Patent licensing of codecs (H.264, HEVC, AAC) is separate from the copyright license and is **not** addressed here. Consult a lawyer before commercial distribution.
5. The retired FFmpegKit (LGPL/GPL wrapper, binaries removed from Maven Central) is deliberately **not** used.

## Not verified
Licenses of transitive dependencies resolved by Gradle were not inspected in this session because Maven repositories
were unreachable from the authoring sandbox. Run `./gradlew :app:dependencies` and review the result before release.
