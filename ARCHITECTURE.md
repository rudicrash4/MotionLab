# Architecture (Phase 1)

```
app            Application, MainActivity, startup modules (native, database, scheduler)
feature        Compose screens (projects, render test, diagnostics), ProjectRepository, RenderDemoView
core/common    Logging, MlException/MlResult, ServiceContainer (DI), ModuleRegistry     [pure Kotlin]
core/task      Priority TaskScheduler: cancellation, progress                           [pure Kotlin]
core/resource  ResourceManager: ids, refs, ref-counted leases, validation               [pure Kotlin]
core/project   Project file DTOs, versioned JSON codec + migrations, crash-safe store   [pure Kotlin]
core/database  Room: project index + asset metadata                                     [Android]
engine/animation   Keyframes, Hold/Linear/Bezier interpolation, property evaluation     [pure Kotlin]
engine/timeline    Immutable timeline model, clock, controller, DTO mapping             [pure Kotlin]
engine/compositing Blend modes + masks (CPU reference for future shaders)               [pure Kotlin]
engine/audio       Mixer state (mute/solo/gain) + PCM16 mixer                           [pure Kotlin]
engine/effects     Effect registry + validated parameters -> Media3 effects             [Android]
engine/text        TypographyConfig, FontManager, TextRenderer (StaticLayout)           [Android]
engine/media       Metadata backends (Android, FFmpeg), resolver, importer, capabilities [Android]
engine/export      ExportPlanner (pure) + Media3Exporter (Transformer)                  [Android]
engine/rendering   EGL core, GL utils, textured quad renderer, RenderThread             [Android]
native/cpp-core    JNI + C++ (allocation registry, optional FFmpeg probe)               [Android + NDK]
```

Dependency direction: app -> feature -> engines -> core. No cycles; UI never touches decoders/GL directly except
`RenderDemoView`, a test view. Pure-Kotlin modules have no Android dependency so they are tested on the JVM.

Key decisions
- **Media3 does the heavy lifting** (MediaCodec pipeline, GL effects, muxing). MotionLab owns the *model* (timeline, keyframes) and maps it onto Media3.
- **Project file is separate from the runtime model** (DTOs + mapper) so the format can be versioned. A newer-schema file is rejected, never truncated.
- **Saving is crash-safe**: temp file, fsync, backup, atomic rename; load falls back to the backup if the main file is corrupt.
- **Failures degrade, not crash**: a module that fails to initialize (e.g. native library) is reported and its dependents skipped.

## Status of each piece
| Area | Status |
|---|---|
| core/*, animation, timeline, compositing, audio | **Working**, unit-tested on the JVM (except the serialization codec, see below) |
| JNI + C++ | **Working** on a host JVM (compiled with g++, exercised through NativeCore); Android/NDK build untested |
| Room, effects, text, media backends, export, rendering, UI | **Written, not compiled** (needs Android SDK); tests written, run in CI |
| Export | Cuts + gaps + resize + brightness/contrast/RGB via Media3. **Not yet**: keyframe animation, masks, blend modes, text/overlay tracks, audio mixing automation (reported as warnings) |
| Placeholders / planned | Custom GLSL effects, particles, graph editor UI, proxy media, baking, autosave service, Oboe audio engine, preview playback with CompositionPlayer |

The 30-60 FPS preview target has **not** been achieved or measured.
