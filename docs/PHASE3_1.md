# Phase 3.1 — Media integration & mobile UX

## 1. What was found and fixed (media pipeline)
Traced: picker -> `AssetImporter` -> `AssetRefDto` -> `EditorSession.putAssets` -> library -> `insertAsset` -> `MediaClip.assetId` -> `PreviewMedia` -> `EditorScene` -> `TexturedQuadRenderer`. The links existed; the media failed to *show* for these reasons, all fixed:
1. **Clip placed away from the playhead.** A 2nd clip of the same kind was pushed behind the existing one (`firstFreeStart`), so at the playhead the preview stayed empty. Now `EditorSession.insertAsset` starts the clip exactly at the playhead and uses another free/unlocked track or creates one (one undo step).
2. **Video frame never changed while playing.** Frames were taken with `OPTION_CLOSEST_SYNC` (nearest key frame, often seconds away). Now `OPTION_CLOSEST` (exact frame), one retriever kept open per video, frame bucket 250 ms (≈4 fps).
3. **Fake content / flicker.** While a bitmap was decoding the scene drew a coloured labelled tile. Now nothing is drawn until the first real frame; during playback the previous real frame stays until the next one is ready.
4. **Photos rotated / wrong aspect.** EXIF orientation was ignored. Probe and decode now apply it (API 24+).
5. **Nobody could find "add".** Media lived in a hidden tab with a small "+ Timeline" text. Now a toolbar "Media" button opens it; chips Images/Videos/Audio/Fonts; row "+" and a big "Add to Timeline" button; the new clip is selected and scrolled into view (horizontal `revealingClip`, vertical `TimelineScroll.revealRow`).
Limits: video preview is a decoded still per 250 ms, not a real-time player; assets imported in Phase 3 from rotated photos keep their old (unswapped) size.

## 2. Media insertion
Add Media -> Image/Video/Audio chip -> "In project" or "On this device" -> tap item -> Add to Timeline (or the row "+"). Track: the selected clip's track if the kind fits, is unlocked and free at that time; else another free unlocked track of the kind; else a new track. Locked selected track -> skipped and the status line says so. Duration: image 5 s, video/audio source duration. Gallery items are referenced (not copied); picker items are copied as before. A device file already in the project (same URI or same name+size) reuses its asset.

## 3. Android permissions
Manifest: `READ_MEDIA_IMAGES/VIDEO/AUDIO` (13+), `READ_MEDIA_VISUAL_USER_SELECTED` (14+), `READ_EXTERNAL_STORAGE` `maxSdkVersion=32`. Never `MANAGE_EXTERNAL_STORAGE`. Requested only after tapping "Allow access" on the "On this device" source, below an explanation; one dialog covers photos+videos. States (pure logic in `core/resource/MediaPermissions.kt`): not asked / denied (try again) / permanently denied (explains system Settings, no more dialogs) / partial (lists the shared items + "Allow all") / full. "Use system picker" (Photo Picker / document picker) is offered in every non-full state. Fonts: document picker only.

## 4. Square preview
The viewport is a square (`min(width, height)` of the preview area, centred). The project canvas keeps its aspect and is letterboxed inside it by the renderer; the touch overlay covers the same square and uses the same `PreviewMath.letterbox` through `PreviewMath.viewMap` for selection, move, scale, rotate, anchor drawing and grid. Background: existing Settings menu (Checker/Black/Gray/White). Renderer classes were not modified.

## 5. Timeline
Rows 60/48 dp (was 80/56), ruler 26 dp (32), header 108 dp (120), icon buttons 34 dp in headers, 40-44 dp elsewhere; toolbar icons. Preview height adjustable by dragging the handle under the toolbar (22-55 % of the screen). Scrolling, pinch zoom, playhead unchanged. Media clips are labelled with the file name.

## 6. Property editor
One category at a time: Transform (position, scale, rotation, anchor, opacity), Content (text/shape clips only), Mask, Blend, Audio, Animation (presets, keyframe editing; auto-opens when a keyframe is selected). **Not offered because not implemented:** Color (brightness/contrast/saturation), audio volume/fades/balance. The Audio category only has the real "Mute track" switch plus a note.

## 7. Icons
All editor icons are vector `ImageVector`s (`Icons.kt`): save, undo, redo, settings, export, play, pause, prev/next frame, prev/next keyframe, keyframe, add media, lock/unlock, visibility, delete, split, more, transform, mask, blend, audio, image, video, font, layers, chevrons, radio/check. No emoji or text glyph icons remain. Menu chevrons are icons too.

## 8. Known limitations
No Android compile/APK/instrumented/profiling run in the sandbox. The system permission dialog and Settings flow are not automated (states are tested through `accessOverride`). Video preview frame rate is low. No real-time GL pixel assertion of the preview. Touch targets in track headers are 34 dp (below the 48 dp guideline) to keep the timeline compact.
