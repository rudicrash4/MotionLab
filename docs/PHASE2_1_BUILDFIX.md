# Phase 2.1 emergency build fix

Error (CI, EditorScreen.kt ~283-284): "Functions which invoke @Composable functions must be marked @Composable" /
"@Composable invocations can only happen from the context of a @Composable function".

Root cause: in `TimelineToolbar`, the overflow menu declared a LOCAL helper `fun item(...) = DropdownMenuItem(...)`.
A local function is not inline, so it does not inherit the composable context of the `DropdownMenu { }` lambda; it calls a
composable (`DropdownMenuItem`, `Text`) without being composable itself.

Fix: the local function is annotated `@Composable` and has a block body; all calls to it are made directly from the
`DropdownMenu` content lambda (a composable context), so no other change is needed.

Regression review (manual, no compiler available locally): every other composable call in the Phase 2.1 files was
checked to be inside a composable lambda or an inline lambda (`forEach`, `let`, `for`); signatures of engine APIs used by the
new UI (TimelineLayout, SnapEngine, MoveSolver, TimelineViewport, Selection, EditorSession) were compared with their
declarations. One functional fix found: the preview overlay did not observe `settingsVersion`, so the grid toggle would not
redraw; it now reads it in the draw lambda.
No engine, rendering, timeline, animation, project, media, audio or effects code was changed.
