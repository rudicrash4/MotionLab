# Phase 2.1 — UI/UX redesign, editing performance, bug fixes

Status: implemented in source. NOT compiled/run in the authoring sandbox (no Android SDK): Compose code was only
syntax-checked; APK, instrumented tests and profiling must come from CI / a device. No benchmark numbers exist for 2.1.

## Root causes of the janky timeline (from code analysis, not profiling)
1. Every pointer event executed a Command: History snapshot + listener publish + autosave reschedule.
2. The whole editor read one `state` object, so each event recomposed header, toolbar, panels and timeline.
3. Playhead/selection read in composition instead of draw phase.
4. Snap targets rebuilt per event; allocations (lists, marks) in draw; `toDocument` on the main thread.

## Fixes
- `EditorSession.preview/commit/cancelPreview` (engine/timeline): a gesture previews ABSOLUTE commands from the committed
  state (no drift), publishes transient state (no history, no autosave, no DB), and records ONE undo step on commit.
  Any other published change (undo/redo/select/load) ends the transaction. Covered by `TransactionTest` (JVM).
- `EditorController` splits state: timeline / selection / headers / layers / canUndo / canRedo / playhead / viewport.
  Autosave is scheduled only when the COMMITTED timeline changes; mapping + file I/O run on `Dispatchers.Default`.
- `TimelinePanel`: reads only `headers` in composition; canvas reads timeline/selection/viewport/playhead in the draw
  lambda; only clips intersecting the viewport are drawn; keyframe marks cached by identity of the clip's properties;
  snap targets computed once per gesture; `try/finally` cancels the preview on cancellation or a second finger (pinch).
- Trim, move, keyframe move, slider drags, Graph (point + bezier handle), Path (point + tangents), preview transform
  handles all use the same transaction → one undo step each.
- Playback poll uses `delay(16)` instead of a `withFrameNanos` loop (which blocked `waitForIdle`).
- Rendering engine (RenderThread, FrameLoop, EglCore, TextureCache, EGL lifecycle) NOT refactored. Only
  `EditorPreviewView`/`EditorScene` (editor layer) gained background modes, GL checkerboard, grid flag, measured text size.

## UI
- Theme: black/charcoal/panel greys, neon-green accent (`Theme.kt`).
- Main editor: header (back, name with ellipsis + save status, save, undo, redo, preview settings, export), preview with
  checkerboard / black / gray / white and grid, selection outline, anchor, handles (Transform tool), playback bar
  (start, prev keyframe, prev frame, play/pause, next frame, next keyframe, end), compact timeline toolbar (+Layer, +Track,
  Split, Delete, Snap, ⋯ overflow), collapsible bottom panel.
- Layer editor tabs: Attributes (all transform properties, numeric + slider, per-property keyframe add/remove/prev/next/
  clear, interpolation), Anchor & Align (align to canvas, 3x3 anchor presets), Graph, Path, Layers.
- Tools shown: Edit, Move, Transform. Mask and Effects are NOT implemented and therefore NOT shown.
- Export button opens an information dialog only: export is not wired into the editor screen (honest, not faked).

## Known limits
- The two reference videos and the CI XML report were not available; instrumented failures were diagnosed from code
  (off-screen toolbar buttons, removing a locked track, `withFrameNanos` blocking idle). Confirm with the next CI run.
- Dragging in the preview ignores parent-layer transforms; text bounds are estimated until the renderer measures them.
- Anchor presets do not compensate position.
- Aspect-ratio switching and speed-graph editing are not implemented (speed graph is read-only).
