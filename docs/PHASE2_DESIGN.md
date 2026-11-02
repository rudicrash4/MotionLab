# MotionLab 2.0 — Phase 2: Timeline Engine + Animation Engine + Editor UI

Rendering stack (RenderThread, FrameLoop, EglCore, TextureCache, EGL lifecycle, SurfaceView handling, target FPS,
adaptive frame rate) is **unchanged**. The only rendering-module edit is an additive method
`TexturedQuadRenderer.drawMatrix(...)`; `draw()` now delegates to it with identical state handling.

## 1. Tracks vs Layers (two separate concepts)

| | Track | Layer |
|---|---|---|
| Meaning | temporal lane that holds clips | node of the transform hierarchy |
| Kinds | video, audio, image, text, object, shape (+ legacy overlay) | CONTENT, GROUP, NULL |
| State | z-order (index 0 = bottom), mute, lock, visible, collapsed, optional `layerId` | parent, visible, locked, collapsed, own keyframes |
| Animation time | clip-local time (keyframes belong to the clip) | timeline time |

A clip's world transform = `layerWorld(track.layerId) × clipLocal`. Groups are layers of type GROUP (nested to any
depth); a NULL layer is an invisible parent. Ungrouping/reparenting keeps the visual position (`keepVisual`).

## 2. Timeline Engine (`engine:timeline`, pure Kotlin)

* Immutable `Timeline` → every edit is a `Command` (`apply(state) -> state`); `History` stores snapshots,
  folds drags by `mergeKey` (one gesture = one undo step), failed commands change nothing.
* Clip ops: add, move (also across tracks), trim start/end (source-limited for video/audio), set duration, split,
  duplicate, delete, copy/paste, snapping (`SnapEngine`: clip edges, playhead, 0), overlap prevention (`MoveSolver`).
* Selection: clips, layers and keyframes together; multi-select; group select (`withLayerGroup`).
* `TimelineViewport`: zoom around a focus point, scroll, ruler step, `Timecode`.
* `TimelineLayout`: row geometry, keyframe marks, **hit-testing** and **virtualisation** (`visibleClips` is a binary
  search; the canvas draws only clips that intersect the visible window).
* `EditorSession`: the single mutable owner (state + history + one playhead clock + clipboards + listeners).

## 3. Animation Engine (`engine:animation`, pure Kotlin)

* Property system: `PropertyRegistry` (name, group, default, min/max, step). Built-in: position X/Y, scale X/Y,
  rotation, anchor X/Y, skew X/Y, opacity, path progress. New properties = `register(PropertyDef)`; values are stored
  by name, so nothing else changes.
* Keyframe: stable `id`, `timeUs`, value, `Interpolation` (Hold, Linear, Bezier; EaseIn/Out/InOut are Bezier presets).
  Evaluation is a pure function of timeline time (never frame callbacks).
  Precedence: animated > constant > registry default.
* Motion path: piecewise cubic bezier with handles, arc-length parametrised (256 samples/segment, measured error < 3%),
  rotate-along-path, speed via the keyframed `motion.progress` property.
* Hierarchy: `Mat3` affine maths, `TransformMath.decompose`, local vs world transforms.
* Presets (17, 6 categories) are recipes that write **ordinary keyframes**; they are undoable and editable in the
  Graph Editor like hand-made ones. `PresetLibrary.register` adds more.
* `GraphMath`: bezier-handle ↔ `Interpolation.Bezier` conversion and fit ranges for the Graph Editor.

## 4. One evaluator

`SceneEvaluator.evaluate(timeline, timeUs) -> SceneFrame` (draw-ordered `RenderItem`s with world matrix and
opacity). The preview (`EditorScene`) calls `session.frame()`; `frameAt(t)` gives the same result for any time. The
persistence test checks that a saved-and-reloaded project produces identical frames.
**Export:** the Media3 export path is still the Phase 1 clip-sequence exporter. It does *not* yet render frames through
`SceneEvaluator`; the planner reports this as a warning (see limits).

## 5. Project system

Schema v2 (`layers`, track flags, keyframe ids, motion path, presets, payload). Migration v1→v2 is additive: empty
layers, ids generated on load, defaults for flags. Phase 1 files load (`ProjectCodecTest`, `ProjectPersistenceTest`).
Autosave: debounced (1.5 s after the last *timeline* change; selection does not count), flush on leaving the editor and
on `ON_STOP`, status shown in the top bar, failures shown and retried on the next flush. Files are written by
`ProjectStore` (temp file + backup, crash-safe, from Phase 1).

## 6. Editor UI (Compose, `feature`)

Areas: preview (SurfaceView on the existing render stack), toolbar, timeline (ruler, tracks, headers), property panel with
keyframe controls, Graph Editor, motion-path editor, layers panel. Narrow screens (< 720 dp): vertical stack;
wide: preview+timeline left, panels right. Touch targets are ≥ 40 dp.

Gesture policy (one pointer handler per canvas, so gestures cannot conflict):
two fingers → pinch zoom + pan (always wins); one finger on clip/handle/diamond → edit; one finger on empty space →
horizontal = scroll time, vertical = *not consumed* (the track list scrolls); ruler → scrub.
Every visible control is wired to a command or to the viewport/playback; nothing is decorative.

## 7. Honest limits / not done

* **No media import**: video/audio/image clips cannot be created in the UI yet (no picker/decoder in this phase). Tracks of
  those kinds can be created and clips moved onto them; the preview draws such clips as labelled tiles.
  Clips the UI can create: text, rectangle, ellipse, object tile.
* **Export does not apply animation** (see §4). Shape/object/text tracks are skipped with a warning.
* Graph Editor: speed graph is read-only (derived curve); bezier handles are edited in the value graph.
* No rubber-band selection; multi-select is a toggle ("Multi") plus "Select all" and layer "Select group".
* The preview renders continuously (FrameLoop from Phase 1), also while idle.
* Keyframe marks are recomputed per draw for visible rows; fine for hundreds of keyframes, not profiled beyond that.
* Nothing in this document is a device measurement. No FPS claim is made for Phase 2.
