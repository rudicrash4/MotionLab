package id.motionlab.engine.timeline

import id.motionlab.core.common.MlException
import id.motionlab.core.project.ProjectDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTest {
    private fun fixture(): Triple<EditorSession, String, String> {
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        val c = s.addClip(t, 1_000_000, 1_000_000)
        return Triple(s, t, c)
    }

    private fun start(s: EditorSession, c: String) = s.timeline.requireClip(c).second.startUs

    @Test fun previewChangesWhatIsShownButNotHistoryOrTheSavedDocument() {
        val (s, _, c) = fixture()
        val label = s.undoLabel
        val events = ArrayList<Boolean>()
        s.addListener { events += s.inTransaction }
        repeat(50) { i -> s.preview(MoveClips(listOf(c), (i + 1) * 10_000L)) }
        assertEquals(1_500_000L, start(s, c))
        assertTrue(s.inTransaction)
        assertEquals("no history entry per pointer event", label, s.undoLabel)
        assertTrue(events.all { it } && events.size == 50)
        val saved = s.toDocument(ProjectDocument(id = "p", name = "n", createdAtMs = 1, modifiedAtMs = 1))
        assertEquals("autosave must never persist a half-finished gesture", 1_000_000L, saved.tracks.single().clips.single().startUs)
        assertEquals(1_000_000L, s.committedState.timeline.requireClip(c).second.startUs)
    }

    @Test fun commitIsExactlyOneUndoStep() {
        val (s, _, c) = fixture()
        repeat(20) { i -> s.preview(MoveClips(listOf(c), (i + 1) * 10_000L)) }
        s.commit(MoveClips(listOf(c), 200_000))
        assertFalse(s.inTransaction)
        assertEquals(1_200_000L, start(s, c))
        s.undo()
        assertEquals(1_000_000L, start(s, c))
        s.redo()
        assertEquals(1_200_000L, start(s, c))
    }

    @Test fun cancelRestoresTheCommittedStateAndLeavesNoHistory() {
        val (s, _, c) = fixture()
        val label = s.undoLabel
        s.preview(MoveClips(listOf(c), 300_000))
        s.cancelPreview()
        assertFalse(s.inTransaction)
        assertEquals(1_000_000L, start(s, c))
        assertEquals(label, s.undoLabel)
    }

    @Test fun aFailingPreviewLeavesTheLastGoodPreviewAndAFailingCommitCancels() {
        val (s, t, c) = fixture()
        s.preview(MoveClips(listOf(c), 100_000))
        try { s.preview(MoveClips(listOf(c), -5_000_000)); throw AssertionError("should fail") } catch (e: MlException) { assertNotNull(e.message) }
        assertEquals("last good preview stays", 1_100_000L, start(s, c))
        try { s.commit(MoveClips(listOf(c), -5_000_000)); throw AssertionError("should fail") } catch (e: MlException) { }
        assertFalse(s.inTransaction)
        assertEquals(1_000_000L, start(s, c))
        s.execute(SetTrackFlags(t, locked = true))
        try { s.preview(MoveClips(listOf(c), 1)); throw AssertionError("locked") } catch (e: MlException) { }
        assertEquals(1_000_000L, start(s, c))
    }

    @Test fun anyOtherEditEndsTheTransaction() {
        val (s, _, c) = fixture()
        s.preview(MoveClips(listOf(c), 100_000))
        s.select(Selection(clipIds = setOf(c))) // any other published change drops the preview
        assertFalse(s.inTransaction)
        assertEquals(1_000_000L, start(s, c))
    }

    @Test fun frameReflectsThePreviewSoTheRendererSeesTheDrag() {
        val (s, _, c) = fixture()
        s.seekTo(1_000_000)
        assertEquals(1, s.frame().items.size)
        s.preview(MoveClips(listOf(c), 2_000_000)) // clip now 3 s..4 s: not visible at 1 s
        assertEquals(0, s.frame().items.size)
        s.cancelPreview()
        assertEquals(1, s.frame().items.size)
    }

    @Test fun trimAndKeyframePreviewsAreAbsoluteSoRepeatedPreviewsDoNotDrift() {
        val (s, _, c) = fixture()
        val owner = AnimationOwner.ClipOwner(c)
        val k = id.motionlab.engine.animation.Keyframe(100_000, 1.0)
        s.execute(SetKeyframe(owner, id.motionlab.engine.animation.PropertyNames.OPACITY, k))
        val ref = KeyframeRef(owner, id.motionlab.engine.animation.PropertyNames.OPACITY, k.id)
        repeat(5) { s.preview(MoveKeyframes(listOf(ref), 200_000)) }
        assertEquals(300_000L, s.anim(owner).properties.track(id.motionlab.engine.animation.PropertyNames.OPACITY).keyframes.single().timeUs)
        repeat(5) { s.preview(TrimClipEnd(c, 1_500_000)) }
        assertEquals(500_000L, s.timeline.requireClip(c).second.durationUs)
        s.cancelPreview()
        assertEquals(1_000_000L, s.timeline.requireClip(c).second.durationUs)
    }
}
