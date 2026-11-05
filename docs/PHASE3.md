# Phase 3 — Media system & multi-layer editing

STATUS: implemented in source. Verified here: JVM unit tests only (see "Verification"). NOT compiled with Android/Compose, no APK,
no instrumented test run, no device profiling in the authoring sandbox. CI must confirm.

## Android permissions
None added. Import uses the system Photo Picker (photos/videos) and ACTION_OPEN_DOCUMENT (audio, fonts), so the user hands
over only the chosen files; no READ_MEDIA_* / READ_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE and no permission dialogs.
Each picker opens only when the user taps its button. Photo Picker URIs on Android 13+ are temporary: the importer first tries
`takePersistableUriPermission`; if the provider refuses, the file is COPIED into app-private storage (`files/media`) and the
project references the copy, so it survives restarts. The original file is never modified or deleted. Fonts are validated and
always copied to `files/fonts`.

## Implemented
- Import (images, videos, audio, fonts) with per-file results; wrong-category and invalid files give a readable reason and
  leave nothing behind. Image bounds are read without decoding pixels. Video/audio metadata via MediaMetadataRetriever +
  MediaExtractor (duration, resolution, rotation, sample rate, channels, audio presence); a codec the device cannot decode is
  rejected with a message.
- Font validation (`FontValidator`: sfnt header, table directory bounds, required tables, outlines, family name); imported
  fonts are registered in `FontManager` and selectable per text clip (Attributes → Font, stored as clip payload `font`).
- Media library tab ("Media"): categories with counts, search, sort (newest/name/duration/size), thumbnails, name,
  duration / resolution / sample rate, "+ Timeline", Preview, Remove (reference only; refused while clips use it), missing-file
  banner with Retry.
- Preview dialog: image (sampled decode ≤ 2048 px, pinch zoom), video (Media3 ExoPlayer + PlayerView: play/pause/seek/position),
  audio (Media3: play/pause, progress, duration). Playback errors are shown, not thrown.
- Timeline: video thumbnails and audio waveforms drawn on clips; loaded in the background, cancellation-aware, bounded caches
  (BoundedCache by bytes; waveform min/max peaks are streamed from MediaCodec and cached in memory + `cacheDir/waveforms`).
- Preview rendering: image clips show the real image; video clips show the platform-extracted frame at 4 fps granularity (still
  frames, NOT real-time video playback); both fall back to the labelled tile while decoding. Never blocks the render thread.
- Multi-layer: add imported media to the right track (one undo step incl. a new track), duplicate layer (track), existing
  reorder/lock/visibility/delete/multi-select.
- Transform: Position X/Y, Scale X/Y, Rotation, Anchor X/Y, Opacity (+ skew) were already keyframeable through the shared
  evaluator; preview handles and numeric fields use it. Unchanged.
- Masks: rectangle / ellipse, position, size, rotation, opacity, feather, invert, enable. Data lives in the clip payload
  (`mask*` keys), so it is saved, undoable, copied by split/duplicate. Rendered by baking the coverage into the clip's texture
  (CPU raster ≤ 512 px + DST_IN) — the baked bitmap is then drawn by the GPU. This is NOT a GPU mask shader.
- Blend modes: Normal, Multiply, Screen, Add are drawn with fixed-function GL blending (`QuadBlend`, exact on the opaque preview
  canvas). Darken, Lighten, Overlay are NOT implemented (GL_MIN/MAX ignore alpha; Overlay needs destination read): they are
  stored but shown disabled "(not supported yet)" and drawn as Normal.
- Persistence: asset metadata fields added to `AssetRefDto` (all optional); blend/mask in payload. Schema stays 2; old files load
  unchanged (`ProjectCodecPhase3Test`). Assets are saved by the autosave path (`assetsVersion`); autosave key unchanged.

## Rendering engine change (minimal)
`TexturedQuadRenderer.drawMatrix` got a 4th parameter `blend: QuadBlend = NORMAL` (default = previous behaviour, same GL state).
RenderThread, FrameLoop, EglCore, TextureCache, EGL lifecycle and surface management are untouched.

## Known limitations
- Export does not render images/video/audio/masks/blend modes: the export pipeline was not extended in this phase, so
  "preview = export" is NOT achieved for the new media and look features. Transform maths are shared (SceneEvaluator).
- Video preview is 4 fps still frames; no audio playback inside the editor timeline.
- Photo Picker imports are copied (disk usage); very large videos take time to copy.
- Library add/remove is not part of undo history.
- Mask is static (not keyframed); GIF is imported as a still image.
- Video import is not covered by an automated test (needs an encoded test video).

## Verification actually performed
- JVM (sandbox): engine/timeline 127 tests (22 new: asset placement, library, persistence, mask/blend commands, mask raster,
  duplicate track, transform via evaluator); core/resource 20 tests (new: classifier, font validator, waveform, bounded cache).
- Parse check of all Kotlin sources (syntax only).
- Instrumented tests written, NOT run: `MediaImportTest` (7: image, invalid image, wrong category, audio, waveform, invalid font,
  system font, missing files), `MediaLibraryUiTest` (5). `ProjectCodecPhase3Test` (core:project) needs kotlinx-serialization: CI.
