package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.Easing
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.animation.PropertyNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimelineTest {
    private fun clip(id: String, start: Long, dur: Long) = MediaClip(id, start, dur, assetId = "asset")

    private fun expectError(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: MlException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun trackRejectsOverlapAndDuplicates() {
        val t = Track("v", "V", TrackKind.VIDEO).addClip(clip("a", 0, 1_000))
        expectError(ErrorCode.INVALID_ARGUMENT) { t.addClip(clip("b", 999, 1_000)) }
        expectError(ErrorCode.INVALID_ARGUMENT) { t.addClip(clip("a", 5_000, 100)) }
        val ok = t.addClip(clip("b", 1_000, 500)) // touching edges are fine
        assertEquals(1_500, ok.endUs)
        assertEquals("b", ok.clipAt(1_000)?.id)
        assertNull(ok.clipAt(1_500))
    }

    @Test
    fun durationIsLongestTrack() {
        val tl = Timeline()
            .addTrack(Track("v", "V", TrackKind.VIDEO))
            .addTrack(Track("a", "A", TrackKind.AUDIO))
            .addClip("v", clip("c1", 0, 2_000))
            .addClip("a", clip("c2", 1_000, 5_000))
        assertEquals(6_000, tl.durationUs)
        assertEquals(0, Timeline().durationUs)
    }

    @Test
    fun moveAndOverlapChecks() {
        val tl = Timeline().addTrack(Track("v", "V", TrackKind.VIDEO))
            .addClip("v", clip("a", 0, 1_000)).addClip("v", clip("b", 2_000, 1_000))
        val moved = tl.moveClip("v", "a", 500)
        assertEquals(500, moved.track("v").clip("a")!!.startUs)
        expectError(ErrorCode.INVALID_ARGUMENT) { tl.moveClip("v", "a", 1_500) }
        expectError(ErrorCode.NOT_FOUND) { tl.moveClip("v", "zzz", 0) }
        expectError(ErrorCode.NOT_FOUND) { tl.track("nope") }
    }

    @Test
    fun clipIdsMustBeUniqueAcrossTracks() {
        try {
            Timeline(
                listOf(
                    Track("v", "V", TrackKind.VIDEO, listOf(clip("x", 0, 10))),
                    Track("a", "A", TrackKind.AUDIO, listOf(clip("x", 0, 10))),
                ),
            )
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun splitKeepsAnimationContinuity() {
        val fade = KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(2_000_000, 1.0)))
        val c = MediaClip("c", 1_000_000, 2_000_000, "asset", sourceStartUs = 100, properties = AnimatedProperties.NONE.with("opacity", fade))
        val tl = Timeline().addTrack(Track("v", "V", TrackKind.VIDEO)).addClip("v", c)
            .splitClip("v", "c", 2_000_000, "c2")
        val left = tl.track("v").clip("c")!!
        val right = tl.track("v").clip("c2")!!
        assertEquals(1_000_000, left.durationUs)
        assertEquals(2_000_000, right.startUs)
        assertEquals(1_000_000, right.durationUs)
        assertEquals(100 + 1_000_000, right.sourceStartUs)
        // value at the split point is 0.5 on both sides
        assertEquals(0.5, left.properties.tracks.getValue("opacity").evaluate(1_000_000), 1e-9)
        assertEquals(0.5, right.properties.tracks.getValue("opacity").evaluate(0), 1e-9)
        assertEquals(1.0, right.properties.tracks.getValue("opacity").evaluate(1_000_000), 1e-9)
        expectError(ErrorCode.INVALID_ARGUMENT) { tl.splitClip("v", "c2", 2_000_000, "c3") } // at the boundary
    }

    @Test
    fun activeClipsRespectOrderAndMute() {
        val tl = Timeline()
            .addTrack(Track("bottom", "B", TrackKind.VIDEO))
            .addTrack(Track("top", "T", TrackKind.OVERLAY))
            .addClip("bottom", clip("b", 0, 1_000))
            .addClip("top", clip("t", 0, 1_000))
        assertEquals(listOf("b", "t"), tl.activeClipsAt(500).map { it.second.id })
        val muted = Timeline(tl.tracks.map { if (it.id == "top") it.copy(muted = true) else it })
        assertEquals(listOf("b"), muted.activeClipsAt(500).map { it.second.id })
        assertTrue(tl.activeClipsAt(1_000).isEmpty())
    }

    // ---- clock & controller -------------------------------------------------------------------

    private class FakeTime : TimeSource {
        var nanos = 0L
        override fun nowNanos() = nanos
        fun advanceMs(ms: Long) {
            nanos += ms * 1_000_000
        }
    }

    @Test
    fun clockAdvancesPausesSeeksAndScalesSpeed() {
        val time = FakeTime()
        val clock = TimelineClock(time)
        assertEquals(0, clock.currentTimeUs())
        time.advanceMs(100)
        assertEquals(0, clock.currentTimeUs()) // paused

        clock.play()
        time.advanceMs(250)
        assertEquals(250_000, clock.currentTimeUs())
        clock.speed = 2.0
        time.advanceMs(100)
        assertEquals(450_000, clock.currentTimeUs())
        clock.pause()
        time.advanceMs(1_000)
        assertEquals(450_000, clock.currentTimeUs())
        clock.seekTo(-5)
        assertEquals(0, clock.currentTimeUs())
        try {
            clock.speed = 0.0
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun controllerWithOneSecond(time: FakeTime): TimelineController {
        val fade = KeyframeTrack.of(listOf(Keyframe(0, 0.0, Easing.EASE_IN_OUT), Keyframe(1_000_000, 1.0)))
        val tl = Timeline().addTrack(Track("v", "V", TrackKind.VIDEO)).addClip(
            "v",
            MediaClip("c", 0, 1_000_000, "a", properties = AnimatedProperties.NONE.with(PropertyNames.OPACITY, fade)),
        )
        return TimelineController(tl, TimelineClock(time))
    }

    @Test
    fun controllerEvaluatesAnimationAndStopsAtEnd() {
        val time = FakeTime()
        val c = controllerWithOneSecond(time)
        c.play()
        time.advanceMs(500)
        val mid = c.snapshot()
        assertTrue(mid.isPlaying)
        assertEquals(1, mid.activeClips.size)
        assertEquals(0.5, mid.activeClips[0].values.getValue(PropertyNames.OPACITY), 1e-4)
        assertEquals(1.0, mid.activeClips[0].values.getValue(PropertyNames.SCALE_X), 0.0) // default

        time.advanceMs(900)
        val end = c.snapshot()
        assertFalse(end.isPlaying)
        assertTrue(end.atEnd)
        assertEquals(1_000_000, end.timeUs)
        assertTrue(end.activeClips.isEmpty()) // clip covers [0, 1s)

        // Playing again from the end restarts
        c.play()
        time.advanceMs(100)
        val again = c.snapshot()
        assertTrue(again.isPlaying)
        assertEquals(100_000, again.timeUs)
    }

    @Test
    fun seekIsClampedAndEmptyTimelineCannotPlay() {
        val time = FakeTime()
        val c = controllerWithOneSecond(time)
        c.seekTo(99_000_000)
        assertEquals(1_000_000, c.snapshot().timeUs)
        c.seekTo(-1)
        assertEquals(0, c.snapshot().timeUs)

        val empty = TimelineController(Timeline(), TimelineClock(time))
        empty.play()
        assertFalse(empty.snapshot().isPlaying)
    }

    // ---- mapping --------------------------------------------------------------------------------

    @Test
    fun mapperRoundTripsTimeline() {
        val fade = KeyframeTrack.of(listOf(Keyframe(0, 0.0, Easing.EASE_IN), Keyframe(1_000_000, 1.0)))
        val tl = Timeline().addTrack(Track("v", "Video", TrackKind.VIDEO, muted = true)).addClip(
            "v",
            MediaClip("c", 10, 1_000_000, "asset-1", 5, AnimatedProperties.NONE.with("opacity", fade)),
        )
        val doc = ProjectDocument(id = "p", name = "n", createdAtMs = 1, modifiedAtMs = 2)
        val withTimeline = TimelineMapper.applyTo(doc, tl)
        assertEquals(1_000_010, withTimeline.config.durationUs)

        val back = TimelineMapper.toTimeline(withTimeline)
        val track = back.track("v")
        assertTrue(track.muted)
        val c = track.clip("c")
        assertNotNull(c)
        assertEquals("asset-1", c!!.assetId)
        assertEquals(5, c.sourceStartUs)
        assertEquals(tl.track("v").clip("c")!!.properties.tracks, c.properties.tracks)
    }

    @Test
    fun mapperRejectsUnknownTrackKind() {
        val doc = ProjectDocument(
            id = "p", name = "n", createdAtMs = 1, modifiedAtMs = 2,
            tracks = listOf(id.motionlab.core.project.TrackDto("t", "T", "HOLOGRAM")),
        )
        expectError(ErrorCode.UNSUPPORTED) { TimelineMapper.toTimeline(doc) }
    }
}
