package id.motionlab.engine.timeline

import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.PropertyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLayoutTest {
    private fun session(): Triple<EditorSession, String, String> {
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        val c = s.addClip(t, 1_000_000, 2_000_000)
        return Triple(s, t, c)
    }

    private val vp = TimelineViewport(pxPerSecond = 100.0)

    @Test fun handlesAndBodyAreDistinguished() {
        val (s, t, c) = session()
        val rows = TimelineLayout.rows(s.timeline, 80f, 40f)
        val y = 10f
        fun hit(x: Float) = TimelineLayout.hit(s.timeline, rows, vp, x, y, 14f, 10f)
        assertEquals(TimelineHit.OnClip(t, c, ClipZone.TRIM_START), hit(105f))
        assertEquals(TimelineHit.OnClip(t, c, ClipZone.BODY), hit(200f))
        assertEquals(TimelineHit.OnClip(t, c, ClipZone.TRIM_END), hit(295f))
        assertEquals(TimelineHit.OnEmpty(t), hit(50f))
    }

    @Test fun tinyClipStillHasABody() {
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        val c = s.addClip(t, 0, 200_000) // 20 px wide at 100 px/s
        val rows = TimelineLayout.rows(s.timeline, 80f, 40f)
        val mid = TimelineLayout.hit(s.timeline, rows, vp, 10f, 5f, 14f, 10f)
        assertEquals(TimelineHit.OnClip(t, c, ClipZone.BODY), mid)
    }

    @Test fun keyframeDiamondWinsInTheLaneAndOnlyWhenExpanded() {
        val (s, t, c) = session()
        val owner = AnimationOwner.ClipOwner(c)
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, Keyframe(500_000, 1.0)))
        s.execute(SetKeyframe(owner, PropertyNames.ROTATION, Keyframe(500_000, 0.0)))
        val rows = TimelineLayout.rows(s.timeline, 80f, 40f)
        val x = vp.timeToPx(1_500_000)
        val h = TimelineLayout.hit(s.timeline, rows, vp, x + 3f, rows[0].laneTop + 5f, 14f, 10f)
        assertTrue(h is TimelineHit.OnKeyframes)
        assertEquals(2, (h as TimelineHit.OnKeyframes).mark.refs.size) // both properties at that time move together
        s.execute(SetTrackFlags(t, collapsed = true))
        val rows2 = TimelineLayout.rows(s.timeline, 80f, 40f)
        val h2 = TimelineLayout.hit(s.timeline, rows2, vp, x, 10f, 14f, 10f)
        assertTrue(h2 is TimelineHit.OnClip)
    }

    @Test fun topRowIsTheLastTrack() {
        val s = EditorSession(ids = IdSource.sequential())
        val a = s.addTrack(TrackKind.SHAPE)
        val b = s.addTrack(TrackKind.TEXT)
        val rows = TimelineLayout.rows(s.timeline, 80f, 40f)
        assertEquals(b, rows[0].trackId)
        assertEquals(a, rows[1].trackId)
        assertEquals(160f, TimelineLayout.totalHeight(rows))
    }

    @Test fun virtualisationReturnsOnlyIntersectingClips() {
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        for (i in 0 until 1000) s.addClip(t, i * 1_000_000L, 900_000)
        val v = TimelineLayout.visibleClips(s.timeline.track(t), 500_000_000, 503_500_000)
        assertTrue(v.size in 3..6)
        assertTrue(v.all { it.endUs >= 500_000_000 && it.startUs <= 503_500_000 })
    }
}

class ClipboardIsolationTest {
    @Test fun copyingClipsAfterKeyframesPastesClips() {
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        val c = s.addClip(t, 0, 1_000_000)
        val owner = AnimationOwner.ClipOwner(c)
        val k = Keyframe(100_000, 1.0)
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, k))
        s.select(Selection(keyframes = setOf(KeyframeRef(owner, PropertyNames.OPACITY, k.id))))
        s.copySelection()
        s.select(Selection(clipIds = setOf(c)))
        s.copySelection()
        s.seekTo(0)
        s.execute(SetKeyframe(owner, PropertyNames.ROTATION, Keyframe(0, 1.0))) // extends nothing; just ensures timeline > 0
        s.seekTo(1_500_000)
        assertTrue(s.paste(keyframeOwner = owner))
        assertEquals("the last copy (a clip) is what gets pasted", 2, s.timeline.track(t).clips.size)
    }
}

class LayoutExtrasTest {
    @Test fun rowsFromFlagsMatchRowsOfTheTimeline() {
        val s = EditorSession(ids = IdSource.sequential())
        s.addTrack(TrackKind.SHAPE); val b = s.addTrack(TrackKind.TEXT)
        s.execute(SetTrackFlags(b, collapsed = true))
        val a = TimelineLayout.rows(s.timeline, 80f, 40f)
        val f = TimelineLayout.rowsFromFlags(s.timeline.tracks.map { it.id to it.collapsed }, 80f, 40f)
        assertEquals(a.map { Triple(it.trackId, it.top, it.height) }, f.map { Triple(it.trackId, it.top, it.height) })
        assertEquals(40f, a[0].height)
    }

    @Test fun localMarksGroupByTimeAndSurviveClipMoves() {
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        val c = s.addClip(t, 0, 2_000_000)
        val o = AnimationOwner.ClipOwner(c)
        s.execute(SetKeyframe(o, id.motionlab.engine.animation.PropertyNames.OPACITY, id.motionlab.engine.animation.Keyframe(300_000, 1.0)))
        s.execute(SetKeyframe(o, id.motionlab.engine.animation.PropertyNames.ROTATION, id.motionlab.engine.animation.Keyframe(300_000, 1.0)))
        val before = s.timeline.requireClip(c).second
        val marks = TimelineLayout.localMarks(o, before.anim)
        assertEquals(1, marks.size); assertEquals(2, marks[0].refs.size)
        s.execute(MoveClips(listOf(c), 500_000))
        val after = s.timeline.requireClip(c).second
        assertTrue("moving a clip keeps the same properties object, so a cache keyed on it stays valid", before.properties === after.properties)
    }

    @Test fun textContentEstimateGrowsWithLinesAndSize() {
        val one = MediaClip("a", 0, 1000, payload = mapOf("text" to "Hi", "size" to "100"))
        val two = one.copy(payload = mapOf("text" to "Hi\nThere you", "size" to "100"))
        val (w1, h1) = PreviewMath.estimateContentSize(one, TrackKind.TEXT, 1920)
        val (w2, h2) = PreviewMath.estimateContentSize(two, TrackKind.TEXT, 1920)
        assertTrue(w2 > w1 && h2 == 2 * h1)
        assertEquals(PreviewMath.contentSize(one, 1920), PreviewMath.estimateContentSize(one, TrackKind.SHAPE, 1920))
    }
}
