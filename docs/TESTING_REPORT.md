# Testing report — Phase 2

Status legend: **RAN** = executed in the authoring sandbox on a JVM; **NOT RUN** = written, but needs the Android SDK/emulator
and has only been through manual review. Nothing marked NOT RUN has been executed by me.

## Unit tests (JVM)

| Module | Tests | Status |
|---|---|---|
| `engine:animation` | 53 (interpolation, keyframes, properties, transforms, motion path, presets, graph maths) | **RAN, all passed** |
| `engine:timeline` | 95 (commands, undo/redo, snapping, layers/groups, evaluator, persistence mapping, viewport, hit-test, virtualisation, clipboard) | **RAN, all passed** |
| `engine:export` planner | 7 (incl. shape/object tracks, motion-path warning) | **RAN, all passed** (planner + config files only) |
| `engine:compositing`, `engine:audio` | 7, 5 | **RAN, passed** |
| `engine:rendering` pure logic (Phase 1.1) | 18 | **RAN earlier, passed; files unchanged since** |
| `engine:text` `TextTest` (8, incl. wrapping width, taller layout with more lines, fallback font result) | **NOT RUN**: needs Robolectric NATIVE graphics (`@GraphicsMode(NATIVE)`) → CI `unit-tests` job |
| `core:project` `ProjectCodecTest` (13, incl. v1→v2 migration) | **NOT RUN**: needs kotlinx.serialization → CI |

The TextTest failure from Phase 1.1 was a test/environment problem, not a weakened assertion: the old run used Robolectric's
legacy graphics where `StaticLayout` does not measure text. The fix is the NATIVE graphics mode plus *stronger* assertions
(wrapped width ≤ limit, line count grows, height grows with line count, fallback typeface result is validated).
It has not been executed here.

## Instrumented tests (`:feature:connectedDebugAndroidTest`, added to the CI instrumented job) — all **NOT RUN**

| Class | Tests | Covers |
|---|---|---|
| `TimelineInteractionTest` | 14 | areas present, tab switching, tap select/deselect, drag move (+ single undo step), trim handles, locked track, pinch zoom, scroll, ruler scrub, split/undo/redo/copy/paste/duplicate/delete, multi-select, select all, track header buttons, add track/clip, zoom buttons, play/pause |
| `KeyframeInteractionTest` | 11 | stopwatch, auto-key, clear animation, presets, diamond select + drag (+ undo), interpolation menu, delete, prev/next key, copy/paste/duplicate keyframes, multi-property diamond |
| `GraphEditorTest` | 7 | same data as timeline, select, value drag, time drag, bezier handle drag, empty-tap seek, pinch |
| `ProjectPersistenceTest` | 5 | full save/load identical frames, v1 file, debounced autosave, selection ≠ save, failure reporting and retry |

Gesture tests inject pixel coordinates computed from the default zoom (100 px/s); they may need tuning on the first CI run.

## Regression

Phase 1.1 rendering sources were not modified apart from the additive `drawMatrix`. The device numbers the user measured
for Phase 1.1 (60.2 FPS, 16.61 ms avg, P95 20.30 ms, max 24.31 ms, 27 dropped frames, 0 errors) are **not re-measured** for
Phase 2; the editor preview shares the stack but a Phase 2 measurement does not exist.

## Build

No Android SDK or network access to Maven in the sandbox: **no APK was built here and no Gradle task ran.**
Android/Compose code (feature module, `EditorPreviewView`, `TextTest`) was reviewed by hand only and may contain compile
errors that CI will reveal.

## Phase 2.1
- JVM (run in sandbox): engine/timeline 105 tests pass (incl. 7 new TransactionTest, layout/clipboard tests); animation incl. GraphMath pass; export planner 7 pass.
- Instrumented tests: updated for the new layout (overflow `tb-more`, `tab-Attributes`, unlock before remove, 2 new tests). NOT run here; result must come from CI.
- Compose sources parse-checked only (no type-check, no APK in sandbox).

## Phase 3
JVM in sandbox: timeline 127 OK, core/resource 20 OK. Not run here: Android compile, APK, instrumented tests (MediaImportTest, MediaLibraryUiTest, earlier suites), ProjectCodecPhase3Test. See docs/PHASE3.md.
