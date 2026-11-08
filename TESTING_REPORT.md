
## Phase 3.2

Run in the authoring sandbox (JVM only, no Android SDK):
* core/resource: 39 tests OK (incl. new ContentSnifferTest, OTF validation).
* engine/animation: 65 tests OK (new EaseCurvesTest).
* engine/timeline: 151 tests OK (new Phase32DirectLayersTest: direct layers, locked track, undo, ease edit/undo/preset).
* Kotlin parse check (kotlinc, syntax errors only) of every changed feature/engine/androidTest file: no syntax errors.

NOT run here (need the Android toolchain; they run in CI): Kotlin/Android compilation, `Phase32Test` (instrumented),
all other instrumented tests, debug APK build, Compose UI behaviour. `Phase31UiTest` `click` import fix is applied but unverified here.
Instrumented tests that depend on the device: `Phase32Test` skips (assume) video when no H.264 encoder exists and OTF when
/system/fonts has no .otf.
