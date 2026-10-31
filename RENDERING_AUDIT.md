# Rendering Engine Audit — Phase 1.1

Scope: `engine/rendering` and the Render Test tab (`feature/RenderDemoView`). No architecture, module or project-system
change, no editor feature added.

## Honest status

| Item | Status |
|---|---|
| Pure logic (`FrameStats`, `AdaptiveFrameRate`, `LruByteCache`): 18 unit tests | **Executed locally on a JVM, all passed** |
| Android code (`EglCore`, `RenderThread`, `FrameLoop`, `TexturedQuadRenderer`, `TextureCache`, `RenderDemoView`) | Type-checked with Kotlin 2.2.0 against hand-written Android stubs (rendering module incl. all instrumented tests). **Not compiled against the real Android SDK, not run** |
| Instrumented tests (13 new + 3 existing) | **Not executed.** They run in the `instrumented-tests` CI job on an API 34 emulator |
| Debug APK | **Not built here** (no Android SDK in this workspace). Built by the `build-apk` CI job, artifact `MotionLab-debug-apk` |
| FPS / frame time / GPU time | **No measurement exists yet.** No number in this repository is a measurement of a device. See "Measuring" below |

The causes of the lag below were found by reading the code. I could not profile the app, so I cannot say how much each one
contributes. They are real defects or avoidable costs either way.

## 1. Problems found

| # | Where | Problem | Evidence |
|---|---|---|---|
| P1 | `RenderThread.swap()` | `swap()` was implemented with `call`, which posts to the render thread and waits on a latch (10 s timeout). `RenderDemoView.doFrame` called it from **inside** a task already running on the render thread, so the thread waited for a message only it could process. The wait ends only through the timeout, after which the swap runs. | Code reading. Not observed on a device |
| P2 | `RenderDemoView` | Frames were driven by `Choreographer` on the UI thread, which posted one task per vsync to the render thread. If a frame takes longer than 16.7 ms the queue grows and latency grows with it: motion looks laggy even though no frame is "slow" in isolation. UI-thread jank also delays frame submission. | Code reading |
| P3 | `RenderDemoView.doFrame` | Rotation advanced 1.5° per callback, so speed depended on the frame rate: any drop looked like slow motion. | Code reading |
| P4 | `RenderThread.ensureContext` | `eglMakeCurrent` on every call (every frame) and a new `RenderContext` object per call. | Code reading |
| P5 | `TexturedQuadRenderer.draw` | `glGetError` after every draw (can stall the pipeline on some drivers), vertices copied from client memory each draw, a new `FloatArray(16)` per draw. | Code reading |
| P6 | `RenderDemoView` | A new `RenderThread` (EGL context, shader compile, texture upload) was created on every `surfaceCreated` and destroyed on `surfaceDestroyed`. Every app switch/rotation paid the full set-up cost. | Code reading |
| P7 | `RenderThread` | The result of `eglSwapBuffers` was a Boolean that nobody evaluated. A lost surface or a lost context (`EGL_CONTEXT_LOST`) was silently ignored and rendering went on with dead objects. | Code reading |
| P8 | `RenderThread` | `detachSurface` and `release` waited up to 10 s on the UI thread. | Code reading |
| P9 | `TexturedQuadRenderer` | No upper bound on GPU texture memory, no way to drop textures after a context loss without calling GL on stale names. | Code reading |
| P10 | whole module | No frame statistics, no idea what the real frame rate was; a loop without a vsync-throttling swap (some emulators) would spin at 100% CPU. | Code reading |

## 2. What changed and why

| File | Change | Fixes |
|---|---|---|
| `RenderThread.kt` (rewritten) | `call` from the render thread runs inline (no self-wait). Cached `RenderContext`. `RenderContext.swap(): SwapResult`. Surface-lost handling (EGL window surface dropped, loop idles until a new surface arrives). Context-loss handling: listener told (`onContextLost`), EGL torn down without GL calls, next frame creates a new context, re-wraps the still-valid Android `Surface`, calls `onContextCreated`. `RenderStatus` snapshot (state, ES version, counters, last error). Surface calls time out after 3 s instead of 10 s. Thread priority `THREAD_PRIORITY_DISPLAY`. `simulateContextLoss()` test hook | P1, P4, P7, P8 |
| `FrameLoop.kt` (new) | Loop runs on the render thread, paced by `eglSwapInterval`. No UI-thread callback, no queue (one pending task at most). Time-based animation argument. Pacing guard: if swap returned in < ½ period the loop sleeps the rest instead of spinning. No surface → 20 Hz idle, no rendering. Adaptive 60 → 30 fps and back through `AdaptiveFrameRate`. Records every frame into `FrameStats` | P2, P3, P10 |
| `EglCore.kt` | `makeCurrent` is a no-op when the surface is already current. `swapBuffers` returns `SwapResult`. `setSwapInterval`. `destroySurface` un-currents first | P4, P7 |
| `GlUtil.kt` | `debugChecks` flag (per-draw `glGetError` is off by default). `GpuCapabilities.hasDisjointTimerQuery` | P5 |
| `TexturedQuadRenderer.kt` | Vertices in a VBO (uploaded once). No allocation per draw. `glGetError` only when `debugChecks`. `abandon()` for context loss. Live texture count/bytes | P5, P9 |
| `TextureCache.kt` (new) | Byte-budgeted LRU (default 32 MB, RGBA8 estimate). Evicted textures are deleted on the GPU. Oversized texture → `INVALID_ARGUMENT`. Source bitmap recycled after upload | P9 |
| `FrameStats.kt`, `AdaptiveFrameRate.kt`, `LruByteCache.kt` (new, pure Kotlin) | Logic without Android dependencies so it can be unit-tested on a JVM | P2, P9, P10 |
| `RenderDemoView.kt` (rewritten) | `FrameLoop` instead of Choreographer. Thread and GL objects live from first `surfaceCreated` to `onDetachedFromWindow`; a surface that is destroyed/recreated only re-attaches. Loop stops when the window is not visible. Scene survives context loss | P2, P3, P6 |
| `Screens.kt` | Render Test tab shows live diagnostics, refreshed every 500 ms | P10 |
| `.github/workflows/build.yml` | Instrumented job saves logcat lines tagged `MlRenderTest` as `render-logcat.txt` (also when tests fail) | measurement |

Not changed: module structure, dependency versions, other tabs, project system.

### What the optimisations can and cannot do

* P1 and P2 are structural; if they were the cause of the lag the improvement is large. P4/P5 save CPU time per frame
  (allocation, a state call, a possible pipeline stall); the gain per frame is small and driver-dependent.
* The demo scene is one 8×8 texture on one quad: if the device was lagging on that, the cause is almost certainly pacing
  and queueing (P1/P2), not fill-rate.
* The adaptive rate only reacts to **observed frame intervals and CPU time**. It does not know the GPU load.

## 3. Diagnostics (Render Test tab)

All numbers are measured with `System.nanoTime` on the device, in a sliding window of the last 120 frames:

* display refresh rate (from `Display.getRefreshRate`), swap interval, target fps
* **measured fps**, average / p95 / max frame interval
* **CPU render time** (time inside `renderFrame`, i.e. issuing GL commands; not GPU execution)
* **swap wait** (time inside `eglSwapBuffers`; includes the vsync wait, so it is *not* GPU time)
* frames total, **dropped frames** (interval > 1.5 × target period, counted as `round(interval/period) − 1`), pacing sleeps, render errors
* surface size, texture memory (bytes) and uploads
* EGL status: state, GLES version, window surface present, contexts created/lost, surfaces attached/lost, last error
* **GPU time: not measured.** `GL_EXT_disjoint_timer_query` is the only way, the Android SDK has no Java binding for it,
  and it is not used. The overlay says whether the driver advertises it; it never prints a GPU time.

## 4. Tests

Unit (JVM, pure Kotlin) — **executed, 18 passed**: `FrameStatsTest` (6), `AdaptiveFrameRateTest` (6), `LruByteCacheTest` (6).

Instrumented (`RenderingLifecycleInstrumentedTest`, plus the 3 existing tests in `RenderingInstrumentedTest`) — **written, not executed**:

| Area | Test |
|---|---|
| render loop | re-entrant `call` does not deadlock (< 2 s; the old code waited for its 10 s timeout) · loop presents frames and does not exceed ~150 frames in 2 s (no busy loop) · loop idles at ~20 Hz without a surface and starts presenting when one arrives |
| surface recreation | 5× attach/detach: context created once, 5 attachments, consumer receives frames every time · ImageReader destroyed under the running loop, replacement surface works |
| EGL lifecycle | state `NOT_CREATED → READY`, ES ≥ 2, GPU info · simulated context loss: listener lost/created counts, 2nd context, same Surface re-wrapped, frames continue · release idempotent, state `RELEASED`, later calls rejected |
| texture upload | byte accounting, delete idempotent, drawing a deleted texture fails · cache: hit does not decode/upload, LRU eviction deletes on the GPU, byte budget, oversize rejected |
| resource cleanup | 50 textures freed by `close()` · `onRelease` callbacks run with a current context · 25 create/release cycles leave no live render threads |
| stability | 15 s with about 5 surface attach/detach rounds and one context loss: no render errors, exactly 1 live texture at the end, heap growth < 64 MB (a leak detector, not a precision measurement) |

They assert behaviour, not frame rates: emulators use software GLES.
The context loss is **simulated** (`simulateContextLoss()` runs the real recovery path); a genuine GPU reset cannot be
provoked from app code, so that path has never met a real `EGL_CONTEXT_LOST`.

## 5. Measuring (what is still to do)

I did not measure anything. To get real numbers:

1. **On your phone:** install the CI APK (`MotionLab-debug-apk`), open *Render Test*, let it run 30 s, and copy the overlay
   values into the table. Repeat with the screen in 60 Hz and (if the phone has it) 120 Hz mode.
2. **On the CI emulator:** after a run, open artifact `instrumented-test-reports` → `render-logcat.txt`. The lines tagged
   `MlRenderTest` contain `FrameLoop` diagnostics from the 2 s loop test and the 15 s stability test. The emulator uses
   software GLES, so these numbers say nothing about a real phone.
3. For before/after: build the previous commit (Phase 1) and read its lag by eye — it has no counters — or compare in
   Android Studio's profiler (System Trace / "Profile GPU Rendering"), which gives GPU numbers this code cannot.

| Device | Display Hz | Surface | Measured fps | avg / p95 / max frame ms | CPU render ms | Dropped | Source |
|---|---|---|---|---|---|---|---|
| _(to be filled from a real run)_ | | | | | | | |

The 60 FPS target is **not** claimed to be reached. It becomes a fact only when a row above shows it.
