package id.motionlab.engine.timeline

import id.motionlab.core.project.AssetRefDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3.2: importing media is the ONLY step; the track and clip are created, selected and undoable as one action. */
class Phase32DirectLayersTest {
    private val video = AssetRefDto("v1", "content://m/1", "VIDEO", "clip.mp4", durationUs = 4_000_000, width = 1920, height = 1080, hasAudio = true)
    private val image = AssetRefDto("i1", "content://m/2", "IMAGE", "pic.png", width = 800, height = 600)
    private val audio = AssetRefDto("a1", "content://m/3", "AUDIO", "song.mp3", durationUs = 9_000_000, sampleRate = 44100, channels = 2)

    private fun session() = EditorSession(ids = IdSource.sequential()).also { it.putAssets(listOf(video, image, audio)) }

    @Test fun anEmptyProjectNeedsNoManualTrackOrLayer() {
        val s = session()
        assertTrue(s.timeline.tracks.isEmpty())
        val ins = s.insertAsset("i1", 1_000_000)
        assertTrue(ins.createdTrack)
        val (track, clip) = s.timeline.requireClip(ins.clipId)
        assertEquals(TrackKind.IMAGE, track.kind)
        assertEquals("i1", clip.assetId)
        assertEquals(1_000_000L, clip.startUs)
        assertEquals(setOf(clip.id), s.selection.clipIds)
    }

    @Test fun eachMediaKindGetsItsOwnTrackKindAndSourceDuration() {
        val s = session()
        val vi = s.insertAsset("v1", 0)
        val v = s.timeline.requireClip(vi.clipId)
        val ai = s.insertAsset("a1", 0)
        val a = s.timeline.requireClip(ai.clipId)
        assertEquals(TrackKind.VIDEO, v.first.kind); assertEquals(4_000_000L, v.second.durationUs)
        assertEquals(TrackKind.AUDIO, a.first.kind); assertEquals(9_000_000L, a.second.durationUs)
        assertEquals("a1", a.second.assetId)
    }

    @Test fun importingSeveralFilesPlacesThemBackToBackOnOneTrack() {
        val s = session()
        var at = 500_000L
        val ids = ArrayList<AssetInsertion>()
        repeat(3) { val ins = s.insertAsset("i1", at); ids += ins; at = ins.startUs + ins.durationUs }
        assertEquals(1, s.timeline.tracks.size)
        assertEquals(listOf(500_000L, 5_500_000L, 10_500_000L), ids.map { it.startUs })
        assertEquals(setOf(ids.last().clipId), s.selection.clipIds) // the last one stays selected
    }

    @Test fun aLockedPreferredTrackIsNeverWrittenTo() {
        val s = session()
        val first = s.insertAsset("i1", 0)
        s.execute(SetTrackFlags(first.trackId, locked = true))
        val second = s.insertAsset("i1", 10_000_000, preferredTrackId = first.trackId)
        assertNotNull(second.note)
        assertFalse(second.trackId == first.trackId)
        assertTrue(s.timeline.track(first.trackId).clips.size == 1)
    }

    @Test fun insertionIsOneUndoStepRemovingTrackAndClip() {
        val s = session()
        s.insertAsset("v1", 0)
        assertEquals(1, s.timeline.tracks.size)
        s.undo()
        assertTrue(s.timeline.tracks.isEmpty())
        s.redo()
        assertEquals(1, s.timeline.tracks.single().clips.size)
    }

    // ---- Ease editor: the curve it edits IS the keyframe interpolation the evaluator reads ----

    private fun easeFixture(): Triple<EditorSession, AnimationOwner, KeyframeRef> {
        val s = session()
        val ins = s.insertAsset("i1", 0)
        val owner = AnimationOwner.ClipOwner(ins.clipId)
        s.execute(SetKeyframe(owner, "opacity", id.motionlab.engine.animation.Keyframe(0, 0.0, id.motionlab.engine.animation.Interpolation.Linear, "ka")))
        s.execute(SetKeyframe(owner, "opacity", id.motionlab.engine.animation.Keyframe(1_000_000, 1.0, id.motionlab.engine.animation.Interpolation.Linear, "kb")))
        return Triple(s, owner, KeyframeRef(owner, "opacity", "ka"))
    }

    private fun mid(s: EditorSession, o: AnimationOwner) = s.anim(o).properties.track("opacity").evaluate(250_000)

    @Test fun draggingTheCurveChangesTheAnimationAndIsOneUndoStep() {
        val (s, owner, ref) = easeFixture()
        val linear = mid(s, owner)
        assertEquals(0.25, linear, 1e-9)
        // a gesture: many previews, one commit
        for (y in listOf(0.1, 0.5, 0.9)) s.preview(SetKeyframeInterpolation(listOf(ref), id.motionlab.engine.animation.EaseCurves.clamped(0.1, y, 0.9, 1.0)))
        s.commit(SetKeyframeInterpolation(listOf(ref), id.motionlab.engine.animation.EaseCurves.clamped(0.1, 0.9, 0.9, 1.0)))
        val eased = mid(s, owner)
        assertTrue("curve must change the value ($linear -> $eased)", eased > linear + 0.1)
        // the same number the editor draws
        val b = s.anim(owner).properties.track("opacity").byId("ka")!!.interpolation
        assertEquals(id.motionlab.engine.animation.EaseCurves.valueAt(b, 0.25), eased, 1e-6)
        s.undo()
        assertEquals(linear, mid(s, owner), 1e-9)
        s.redo()
        assertEquals(eased, mid(s, owner), 1e-9)
    }

    @Test fun presetAppliesToTheSelectedIntervalOnly() {
        val (s, owner, ref) = easeFixture()
        s.execute(SetKeyframe(owner, "opacity", id.motionlab.engine.animation.Keyframe(2_000_000, 0.0, id.motionlab.engine.animation.Interpolation.Linear, "kc")))
        val p = id.motionlab.engine.animation.EaseCurves.BUILT_IN.first { it.id == "ease-in" }
        s.execute(SetKeyframeInterpolation(listOf(ref), p.interpolation))
        val tr = s.anim(owner).properties.track("opacity")
        assertEquals(p.interpolation, tr.byId("ka")!!.interpolation)
        assertEquals(id.motionlab.engine.animation.Interpolation.Linear, tr.byId("kb")!!.interpolation) // next interval untouched
        assertEquals("Ease In", id.motionlab.engine.animation.EaseCurves.match(tr.byId("ka")!!.interpolation)?.name)
        assertTrue(tr.evaluate(250_000) < 0.25) // ease-in starts slower than linear
    }
}
