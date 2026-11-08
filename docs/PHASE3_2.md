# Phase 3.2 — Direct media layers & Ease graph editor

Base: Phase 3.1. No new dependencies, no new modules; rendering engine untouched.

## 1. Direct media layer workflow
Import (Photo Picker, "Browse files", or the device gallery) -> the file is probed in the background -> it becomes a project
asset -> **a clip is inserted at the playhead on a suitable track (created automatically)** -> selected -> revealed in the
timeline -> shown in the preview by the existing `PreviewMedia` path. Several files are placed back to back from the playhead;
the last one stays selected. Images default to 5 s; video and audio keep the source duration. A locked preferred track is
never written to (another or a new track is used and a note is shown). One import = one undo step per file.
Code: `MediaLibraryController.import(..., insert: InsertTarget)`, `EditorSession.insertAsset` (unchanged API).

## 2. Manual layer creation UI
The standalone "Add empty track" toolbar button is gone. Empty tracks remain available, by kind, under the overflow menu
("Advanced: add empty track…"). Text/shape creation (`tb-add-clip`) is unchanged because those layers have no media file.
Track/Layer models and commands are untouched.

## 3. Media formats
Content is inspected, labels are only hints: `ContentSniffer` (core/resource, pure, JVM-tested) reads the first 64 bytes and
recognises JPEG, PNG, GIF, BMP, WebP, HEIF/AVIF, MP4, QuickTime, Matroska/WebM, AVI, WAV, M4A, Ogg, FLAC, MP3, ADTS-AAC, AMR,
AIFF and TTF/OTF signatures. Then the Android probes decide whether the device can really decode it (BitmapFactory bounds,
MediaExtractor/MediaMetadataRetriever, MediaCodecList). A recognised container does **not** guarantee a decodable codec:
undecodable files give a specific message and create no layer. Limits: GIF is imported as a still (first frame); HEIC/AVIF,
MKV, AVI, OGG, FLAC etc. work only where the device's decoders support them. No FFmpeg was added in this phase.
Files are opened by URI; only the first bytes are read for sniffing; no extra copy is made.

## 4. Fonts
Removed from the Media panel. A selected text layer's Content section has "Font family" (menu), **Import Font** (TTF/OTF, validated
by `FontValidator` before registration), the Imported fonts list with a preview in each font, and removal. A newly imported
font is applied to the layer immediately. Fonts are FONT assets of the project (saved with it, re-registered on load).

## 5. Scrollable panels
Property sections (Transform, Content/Text, Mask, Blend, Audio, Animation) were already `verticalScroll`; kept. New: Media panel is
one `LazyColumn` (controls, status, list, device gallery) with only the "Add to Timeline" bar pinned; Path and Layers panels
scroll; the Graph/Ease editor scrolls. Canvases consume touches only on their own handles/points, so scrolling elsewhere works.

## 6. Ease / graph editor
Bottom Graph tab = tabs **Ease | Value | Speed | Position** (the editor screen itself is the preview / keyframe timeline / graph
layout). All four are functional:
* Ease: unit-square cubic-bezier editor for the interval from the selected keyframe to the next. Start/end points, two draggable
  handles with guide lines, diagonal + centre reference guides, live curve, playhead dot, text `cubic-bezier(x1, y1, x2, y2)`.
  Preset list (Linear, Ease, Ease In, Ease Out, Ease In Out, Quad/Back variants) with curve thumbnails and the matching preset
  highlighted; vertical list on the right when the width is >= 380 dp, a scrollable strip otherwise. "Save preset" stores the
  current curve as a custom preset (SharedPreferences, app-wide, deletable).
* Value / Speed: the previous graph (drag points, handles, pinch zoom) unchanged.
* Position: path of the layer's X/Y sampled from the real tracks; only when position is animated, otherwise it says so.
Data flow: handle drag -> `SetKeyframeInterpolation` via `beginGesture()` (live preview, single undo step on release) -> the same
`Interpolation.Bezier` the evaluator/renderer uses. `EaseCurves` (engine/animation) holds the pure maths (clamp x 0..1, y -1..2,
css text, sampling, speed, preset matching, custom preset codec) reusing `BezierEasing`.

## Not done / honest limits
* The reference screenshots were not visible to me; the layout follows the written spec.
* Preview area inside the graph tab is the main editor preview (not a second embedded preview); the keyframe timeline likewise.
* Bezier handles are edited in the normalised unit square; Value-tab handles still use GraphMath (value/time space).
* Custom presets are not stored inside the project file.
* GIF animation, FFmpeg-based decoding of codecs the device lacks: not implemented.
* Nothing here was compiled with the Android toolchain in my sandbox (see TESTING_REPORT.md).
