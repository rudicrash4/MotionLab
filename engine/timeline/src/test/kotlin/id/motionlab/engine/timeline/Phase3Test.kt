package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.animation.PropertyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Phase3Test {
    private fun doc() = ProjectDocument(id = "p", name = "n", createdAtMs = 1, modifiedAtMs = 1)
    private fun video() = AssetRefDto("v1", "content://m/1", "VIDEO", "clip.mp4", durationUs = 4_000_000, width = 3840, height = 2160, hasAudio = true)
    private fun image() = AssetRefDto("i1", "content://m/2", "IMAGE", "pic.png", width = 1000, height = 1000)
    private fun audio() = AssetRefDto("a1", "content://m/3", "AUDIO", "song.mp3", durationUs = 9_000_000, sampleRate = 44100, channels = 2)
    private fun session() = EditorSession(ids = IdSource.sequential()).also { it.putAssets(listOf(video(), image(), audio())) }

    // ---- adding imported media ----
    @Test fun imageVideoAudioGoToTheRightTracksWithFittedSizeAndDuration() {
        val s = session()
        val v = s.addAssetClip("v1", 0)
        val i = s.addAssetClip("i1", 0)
        val a = s.addAssetClip("a1", 0)
        val (vt, vc) = s.timeline.requireClip(v)
        assertEquals(TrackKind.VIDEO, vt.kind)
        assertEquals(4_000_000L, vc.durationUs)
        assertEquals(4_000_000L, vc.sourceDurationUs)
        assertEquals("1920", vc.payload["w"]); assertEquals("1080", vc.payload["h"])
        val (it, ic) = s.timeline.requireClip(i)
        assertEquals(TrackKind.IMAGE, it.kind)
        assertEquals("1080", ic.payload["w"]); assertEquals(5_000_000L, ic.durationUs)
        assertEquals(TrackKind.AUDIO, s.timeline.requireClip(a).first.kind)
    }

    @Test fun addingMediaIsOneUndoStepIncludingTheNewTrack() {
        val s = session()
        s.addAssetClip("v1", 0)
        assertEquals(1, s.timeline.tracks.size)
        s.undo()
        assertEquals(0, s.timeline.tracks.size)
        s.redo()
        assertEquals(1, s.timeline.tracks.single().clips.size)
    }

    // Phase 3.1: the old behaviour pushed a second clip BEHIND the first one, so the preview at the playhead stayed empty.
    @Test fun secondClipAtTheSameTimeGoesToANewTrackStartingAtThePlayheadNotPushedLater() {
        val s = session()
        s.addAssetClip("v1", 0)
        val second = s.insertAsset("v1", 0)
        assertEquals(2, s.timeline.tracks.size)
        assertTrue(second.createdTrack)
        assertEquals(0L, second.startUs)
        assertEquals(0L, s.timeline.requireClip(second.clipId).second.startUs)
        for (t in s.timeline.tracks) assertEquals("no overlap inside a track", t.clips.size, 1)
    }

    @Test fun clipReusesAnExistingTrackWhenTheTimeIsFree() {
        val s = session()
        s.addAssetClip("v1", 0)
        val later = s.insertAsset("v1", 4_000_000L)
        assertEquals(1, s.timeline.tracks.size)
        assertEquals(false, later.createdTrack)
        assertEquals(4_000_000L, s.timeline.requireClip(later.clipId).second.startUs)
    }

    @Test fun lockedPreferredTrackIsSkippedWithAnExplanation() {
        val s = session()
        s.addAssetClip("i1", 0)
        val tid = s.timeline.tracks.single().id
        s.execute(SetTrackFlags(tid, locked = true))
        val r = s.insertAsset("i1", 10_000_000L, preferredTrackId = tid)
        assertTrue(r.createdTrack)
        assertTrue(r.note!!.contains("locked"))
        assertEquals(2, s.timeline.tracks.size)
        assertEquals(setOf(r.clipId), s.selection.clipIds)
    }

    @Test fun selectedFreeTrackOfTheRightKindIsPreferred() {
        val s = session()
        s.addAssetClip("i1", 0)
        s.insertAsset("i1", 0) // second image track
        val first = s.timeline.tracks.first().id
        val r = s.insertAsset("i1", 6_000_000L, preferredTrackId = first)
        assertEquals(first, r.trackId)
        assertEquals(false, r.createdTrack)
    }

    @Test fun audioClipKeepsTheAssetDurationOnAnAudioTrack() {
        val s = session()
        val r = s.insertAsset("a1", 1_000_000L)
        val (t, c) = s.timeline.requireClip(r.clipId)
        assertEquals(TrackKind.AUDIO, t.kind)
        assertEquals(9_000_000L, c.durationUs)
        assertEquals("a1", c.assetId)
        assertEquals(1_000_000L, c.startUs)
    }

    @Test fun fontsUnknownAndDurationlessMediaAreRejected() {
        val s = session()
        s.putAssets(listOf(AssetRefDto("f1", "file:///f.ttf", "FONT", "f.ttf", fontFamily = "F"), AssetRefDto("v0", "content://x", "VIDEO", "bad.mp4")))
        for (id in listOf("f1", "v0", "nope")) {
            try { s.addAssetClip(id, 0); fail("expected failure for $id") } catch (e: MlException) { assertTrue(e.code == ErrorCode.UNSUPPORTED || e.code == ErrorCode.NOT_FOUND) }
        }
        assertEquals("a failed add must leave the timeline untouched", 0, s.timeline.tracks.size)
    }

    // ---- library ----
    @Test fun removingAssetKeepsClipsSafeAndNeverTouchesFiles() {
        val s = session()
        val c = s.addAssetClip("v1", 0)
        try { s.removeAsset("v1"); fail() } catch (e: MlException) { assertEquals(ErrorCode.INVALID_ARGUMENT, e.code) }
        assertNotNull(s.asset("v1"))
        s.execute(DeleteClips(listOf(c)))
        s.removeAsset("v1")
        assertNull(s.asset("v1"))
        assertEquals(2, s.assets.size)
    }

    @Test fun libraryChangesNotifyListenersAndBumpTheVersion() {
        val s = EditorSession(ids = IdSource.sequential())
        var calls = 0
        s.addListener { calls++ }
        val v0 = s.assetsVersion
        s.putAssets(listOf(image()))
        assertEquals(1, calls); assertEquals(v0 + 1, s.assetsVersion)
    }

    // ---- persistence ----
    @Test fun assetsMasksAndBlendModesSurviveSaveAndReload() {
        val s = session()
        val c = s.addAssetClip("i1", 0)
        s.execute(SetBlendMode(listOf(c), BlendMode.MULTIPLY))
        val m = MaskSpec(MaskShape.ELLIPSE, true, 0.1, -0.2, 0.5, 0.4, 30.0, 0.8, 0.25, true)
        s.execute(SetMask(c, m))
        val loaded = s.toDocument(doc()) // the JSON text round trip is covered by core:project's ProjectCodecPhase3Test (CI)
        assertEquals(3, loaded.assets.size)
        assertEquals(9_000_000L, loaded.assets.first { it.id == "a1" }.durationUs)
        assertEquals(44100, loaded.assets.first { it.id == "a1" }.sampleRate)
        val s2 = EditorSession.fromDocument(loaded)
        val clip = s2.timeline.tracks.single().clips.single()
        assertEquals(BlendMode.MULTIPLY, clip.blendMode)
        assertEquals(m, clip.mask)
        assertEquals("i1", clip.assetId)
        assertEquals(3, s2.assets.size)
    }

    @Test fun damagedMaskPayloadDoesNotBreakLoading() {
        val clip = MediaClip("c", 0, 1000, payload = mapOf("mask" to "ELLIPSE", "mask.w" to "-5", "mask.opacity" to "abc", "blend" to "WHAT"))
        assertEquals(BlendMode.NORMAL, clip.blendMode)
        assertNotNull(clip.mask) // clamped, not crashed
        assertEquals(1.0, clip.mask!!.opacity, 0.0)
    }

    // ---- masks & blend through undo/redo ----
    @Test fun maskAndBlendEditsAreUndoableAndSurviveSplitAndDuplicate() {
        val s = session()
        val c = s.addAssetClip("i1", 0)
        s.execute(SetMask(c, MaskSpec()))
        s.execute(SetBlendMode(listOf(c), BlendMode.SCREEN))
        s.undo()
        assertEquals(BlendMode.NORMAL, s.timeline.requireClip(c).second.blendMode)
        s.redo()
        s.seekTo(2_000_000)
        s.select(Selection(clipIds = setOf(c)))
        assertTrue(s.splitAtPlayhead())
        val clips = s.timeline.tracks.single().clips
        assertTrue(clips.all { it.blendMode == BlendMode.SCREEN && it.mask != null })
        s.execute(SetMask(c, null))
        assertNull(s.timeline.requireClip(c).second.mask)
    }

    @Test fun maskOnLockedTrackIsRejected() {
        val s = session()
        val c = s.addAssetClip("i1", 0)
        val t = s.timeline.requireClip(c).first.id
        s.execute(SetTrackFlags(t, locked = true))
        try { s.execute(SetMask(c, MaskSpec())); fail() } catch (e: MlException) { assertEquals(ErrorCode.INVALID_ARGUMENT, e.code) }
    }

    @Test fun maskPreviewIsATransactionWithOneUndoStep() {
        val s = session()
        val c = s.addAssetClip("i1", 0)
        repeat(10) { i -> s.preview(SetMask(c, MaskSpec(w = 0.1 + i * 0.05))) }
        s.commit(SetMask(c, MaskSpec(w = 0.55)))
        s.undo()
        assertNull(s.timeline.requireClip(c).second.mask)
    }

    // ---- blend modes ----
    @Test fun onlyExactModesAreMarkedSupported() {
        assertEquals(setOf(BlendMode.DARKEN, BlendMode.LIGHTEN, BlendMode.OVERLAY), BlendMode.entries.filter { !it.exactOnGpu }.toSet())
        assertEquals(BlendMode.NORMAL, BlendMode.parse(null))
    }

    // ---- multi layer ----
    @Test fun duplicateTrackCopiesClipsWithNewIdsAndIsUndoable() {
        val s = session()
        val c = s.addAssetClip("v1", 0)
        s.execute(SetBlendMode(listOf(c), BlendMode.ADD))
        val src = s.timeline.requireClip(c).first.id
        val copy = s.duplicateTrack(src)
        assertEquals(2, s.timeline.tracks.size)
        val copied = s.timeline.track(copy).clips.single()
        assertTrue(copied.id != c)
        assertEquals(BlendMode.ADD, copied.blendMode)
        assertEquals(s.timeline.trackIndex(src) + 1, s.timeline.trackIndex(copy))
        s.undo()
        assertEquals(1, s.timeline.tracks.size)
    }

    @Test fun severalIndependentLayersOfEveryKindCoexist() {
        val s = session()
        s.putAssets(listOf(AssetRefDto("v2", "content://m/9", "VIDEO", "b.mp4", durationUs = 1_000_000, width = 100, height = 100)))
        val t1 = s.addTrack(TrackKind.VIDEO); val t2 = s.addTrack(TrackKind.VIDEO)
        s.execute(AddClip(t1, MediaClip("c1", 0, 1_000_000, "v1")))
        s.execute(AddClip(t2, MediaClip("c2", 500_000, 1_000_000, "v2")))
        val tx = s.addTrack(TrackKind.TEXT)
        s.execute(AddClip(tx, MediaClip("c3", 0, 2_000_000, payload = mapOf("text" to "Hi"))))
        val frame = s.frameAt(600_000)
        assertEquals(3, frame.items.size)
        s.execute(SetTrackFlags(t2, visible = false))
        assertEquals(2, s.frameAt(600_000).items.size)
    }

    // ---- transform values come from the shared evaluator ----
    @Test fun transformPropertiesAreEvaluatedByTheSharedEvaluator() {
        val s = session()
        val c = s.addAssetClip("i1", 0)
        val owner = AnimationOwner.ClipOwner(c)
        s.seekTo(1_000_000)
        s.setValueAtPlayhead(owner, PropertyNames.POSITION_X, 120.0)
        s.setValueAtPlayhead(owner, PropertyNames.SCALE_X, 2.0)
        s.setValueAtPlayhead(owner, PropertyNames.ROTATION, 90.0)
        s.setValueAtPlayhead(owner, PropertyNames.OPACITY, 0.5)
        val item = s.frameAt(1_000_000).items.single()
        assertEquals(0.5, item.opacity, 1e-9)
        assertEquals(120.0, item.world.e, 1e-6)
        s.undo(); s.undo(); s.undo(); s.undo()
        assertEquals(0.0, s.frameAt(1_000_000).items.single().world.e, 1e-6)
    }
}

class MaskRasterizerTest {
    private fun a(arr: ByteArray, w: Int, x: Int, y: Int) = arr[y * w + x].toInt() and 0xFF

    @Test fun rectangleKeepsInsideAndHidesOutside() {
        val w = 100; val h = 100
        val m = MaskRasterizer.alpha(MaskSpec(MaskShape.RECTANGLE, w = 0.5, h = 0.5), w, h)
        assertEquals(255, a(m, w, 50, 50))
        assertEquals(0, a(m, w, 5, 5))
        assertEquals(0, a(m, w, 95, 50))
    }

    @Test fun ellipseCornersAreHiddenCentreVisible() {
        val m = MaskRasterizer.alpha(MaskSpec(MaskShape.ELLIPSE, w = 1.0, h = 1.0), 80, 80)
        assertEquals(255, a(m, 80, 40, 40))
        assertEquals(0, a(m, 80, 1, 1))
    }

    @Test fun featherCreatesAGradientAcrossTheEdge() {
        val w = 100
        val m = MaskRasterizer.alpha(MaskSpec(w = 0.5, h = 1.0, feather = 0.3), w, 20)
        val edge = 75 // right edge of the 50 px wide mask centred in 100
        val values = (60..90).map { a(m, w, it, 10) }
        assertTrue(values.first() > 200 && values.last() < 50)
        assertTrue("monotonically falling", values.zipWithNext().all { (p, q) -> q <= p })
        assertTrue("edge pixel is mid-transition", a(m, w, edge, 10) in 60..190)
    }

    @Test fun invertSwapsInsideAndOutside() {
        val m = MaskRasterizer.alpha(MaskSpec(w = 0.5, h = 0.5, inverted = true), 100, 100)
        assertEquals(0, a(m, 100, 50, 50)); assertEquals(255, a(m, 100, 5, 5))
    }

    @Test fun opacityScalesTheMaskEffectAndZeroMeansNoEffect() {
        val half = MaskRasterizer.alpha(MaskSpec(w = 0.5, h = 0.5, opacity = 0.5), 100, 100)
        assertEquals(128, a(half, 100, 5, 5))
        val none = MaskRasterizer.alpha(MaskSpec(w = 0.5, h = 0.5, opacity = 0.0), 100, 100)
        assertEquals(255, a(none, 100, 5, 5))
    }

    @Test fun rotationMovesTheShape() {
        // a wide thin bar rotated 90 degrees becomes tall and thin
        val bar = MaskSpec(w = 0.8, h = 0.1, rotationDeg = 90.0)
        val m = MaskRasterizer.alpha(bar, 100, 100)
        assertEquals(255, a(m, 100, 50, 15)); assertEquals(0, a(m, 100, 15, 50))
    }

    @Test fun invalidSpecsAreRejected() {
        for (bad in listOf({ MaskSpec(w = 0.0) }, { MaskSpec(opacity = 2.0) }, { MaskSpec(feather = -1.0) }, { MaskSpec(cx = Double.NaN) })) {
            try { bad(); fail() } catch (e: IllegalArgumentException) { }
        }
        assertFalse(MaskSpec(enabled = false).isActive)
    }
}
