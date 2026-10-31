package id.motionlab.engine.export

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.engine.animation.AnimatedProperties
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeTrack
import id.motionlab.engine.timeline.MediaClip
import id.motionlab.engine.timeline.Timeline
import id.motionlab.engine.timeline.Track
import id.motionlab.engine.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExportPlannerTest {
    private val assets = mapOf(
        "v" to PlannerAsset("content://video"),
        "i" to PlannerAsset("content://image", isImage = true),
        "a" to PlannerAsset("content://audio"),
    )

    private fun plan(tl: Timeline) = ExportPlanner.plan(tl) { assets[it] }

    @Test
    fun gapsAreInsertedBetweenClipsAndLeadingGapToo() {
        val tl = Timeline().addTrack(Track("t", "V", TrackKind.VIDEO))
            .addClip("t", MediaClip("c1", 1_000, 2_000, "v", sourceStartUs = 500))
            .addClip("t", MediaClip("c2", 5_000, 1_000, "i"))
        val r = plan(tl)
        val seg = r.plan.sequences.single().segments
        assertEquals(listOf(true, false, true, false), seg.map { it.isGap })
        assertEquals(listOf(1_000L, 2_000L, 2_000L, 1_000L), seg.map { it.durationUs })
        assertEquals(500, seg[1].sourceStartUs)
        assertTrue(seg[3].isImage)
        assertEquals(6_000, r.plan.durationUs)
        assertFalse(r.plan.isEmpty)
    }

    @Test
    fun mutedAndTextTracksAreSkippedWithWarnings() {
        val tl = Timeline()
            .addTrack(Track("m", "Muted", TrackKind.VIDEO, muted = true))
            .addTrack(Track("t", "Titles", TrackKind.TEXT))
            .addTrack(Track("v", "V", TrackKind.VIDEO))
            .addClip("m", MediaClip("m1", 0, 1_000, "v"))
            .addClip("t", MediaClip("t1", 0, 1_000, "v"))
            .addClip("v", MediaClip("v1", 0, 1_000, "v"))
        val r = plan(tl)
        assertEquals(listOf("v"), r.plan.sequences.map { it.trackId })
        assertTrue(r.warnings.any { it.contains("Titles") })
    }

    @Test
    fun animationProducesWarningNotSilence() {
        val anim = AnimatedProperties.NONE.with("opacity", KeyframeTrack.of(listOf(Keyframe(0, 0.0), Keyframe(10, 1.0))))
        val tl = Timeline().addTrack(Track("v", "V", TrackKind.VIDEO))
            .addClip("v", MediaClip("c", 0, 1_000, "v", properties = anim))
        assertTrue(plan(tl).warnings.any { it.contains("not applied") })
    }

    @Test
    fun unknownAssetAndEmptyTimelineFail() {
        val bad = Timeline().addTrack(Track("v", "V", TrackKind.VIDEO)).addClip("v", MediaClip("c", 0, 10, "ghost"))
        try {
            plan(bad)
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.NOT_FOUND, e.code)
        }
        try {
            plan(Timeline())
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun configValidation() {
        ExportConfig("/tmp/out.mp4").validate()
        for (bad in listOf(
            ExportConfig(""), ExportConfig("/tmp/out.mov"), ExportConfig("/tmp/o.mp4", outputHeight = 10),
            ExportConfig("/tmp/o.mp4", videoBitrate = 5), ExportConfig("/tmp/o.mp4", frameRate = 0),
        )) {
            try {
                bad.validate()
                fail("should reject $bad")
            } catch (e: MlException) {
                assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
            }
        }
    }
}
