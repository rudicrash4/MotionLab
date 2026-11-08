package id.motionlab.feature

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.motionlab.engine.animation.Easing
import id.motionlab.engine.animation.GraphMath
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetKeyframe
import id.motionlab.engine.timeline.SetKeyframeInterpolation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The Graph Editor works on the SAME keyframes as the timeline. The tests compute screen positions with the same
 * fit functions the editor uses for its initial window (GraphMath.fitTimeRange / fitValueRange).
 */
@RunWith(AndroidJUnit4::class)
class GraphEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private val prop = PropertyNames.OPACITY
    private lateinit var a: Keyframe
    private lateinit var b: Keyframe

    private fun fixture(): EditorFixture {
        val f = EditorFixture(rule)
        val owner = AnimationOwner.ClipOwner(f.clipId)
        a = Keyframe(0, 1.0); b = Keyframe(1_000_000, 0.0)
        f.session.execute(SetKeyframe(owner, prop, a)); f.session.execute(SetKeyframe(owner, prop, b))
        f.session.select(Selection(clipIds = setOf(f.clipId)))
        f.show()
        rule.onNodeWithTag("tab-Graph").performClick()
        rule.onNodeWithTag("graph-mode-value").performClick() // the editor opens on the Ease tab
        rule.waitForIdle()
        return f
    }

    private fun track(f: EditorFixture) = f.session.anim(AnimationOwner.ClipOwner(f.clipId)).properties.track(prop)

    /** Screen position of (time, value) in the graph canvas, using the initial fit window. */
    private fun pos(f: EditorFixture, tUs: Long, v: Double, track: id.motionlab.engine.animation.KeyframeTrack): Offset {
        val size = rule.onNodeWithTag("graph-canvas").fetchSemanticsNode().size
        val (t0, t1) = GraphMath.fitTimeRange(track)
        val (v0, v1) = GraphMath.fitValueRange(track)
        return Offset(((tUs - t0).toDouble() / (t1 - t0) * size.width).toFloat(), (size.height - (v - v0) / (v1 - v0) * size.height).toFloat())
    }

    @Test
    fun graphShowsTheAnimatedPropertyAndBothModes() {
        fixture()
        rule.onNodeWithTag("graph-editor").assertExists()
        rule.onNodeWithTag("graph-prop-opacity").assertExists()
        rule.onNodeWithTag("graph-mode-speed").performClick()
        rule.onNodeWithTag("graph-mode-value").performClick()
        rule.onNodeWithTag("graph-fit").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("graph-canvas").assertExists()
    }

    @Test
    fun tappingAGraphPointSelectsTheSameKeyframeTheTimelineShows() {
        val f = fixture()
        val p = pos(f, b.timeUs, b.value, track(f))
        rule.onNodeWithTag("graph-canvas").performTouchInput { click(p) }
        rule.waitForIdle()
        assertEquals(setOf(KeyframeRef(AnimationOwner.ClipOwner(f.clipId), prop, b.id)), f.controller.state.selection.keyframes)
    }

    @Test
    fun draggingAGraphPointChangesItsValueAsOneUndoStep() {
        val f = fixture()
        val tr = track(f)
        val p = pos(f, b.timeUs, b.value, tr)
        rule.onNodeWithTag("graph-canvas").performTouchInput {
            down(p); moveBy(Offset(0f, -30f)); moveBy(Offset(0f, -30f)); up()
        }
        rule.waitForIdle()
        val moved = track(f).byId(b.id)!!
        assertTrue("value should rise, was ${moved.value}", moved.value > 0.05)
        assertEquals("time must not change on a vertical drag", 1_000_000L, moved.timeUs)
        f.session.undo()
        assertEquals(0.0, track(f).byId(b.id)!!.value, 1e-9)
    }

    @Test
    fun draggingAGraphPointSideways_movesTheKeyframeInTime() {
        val f = fixture()
        val tr = track(f)
        val p = pos(f, b.timeUs, b.value, tr)
        rule.onNodeWithTag("graph-canvas").performTouchInput { down(p); moveBy(Offset(-60f, 0f)); up() }
        rule.waitForIdle()
        val moved = track(f).byId(b.id)!!
        assertTrue("moved earlier: ${moved.timeUs}", moved.timeUs < 1_000_000L && moved.timeUs > 0L)
        assertEquals("stable id", b.id, moved.id)
    }

    @Test
    fun draggingABezierHandleEditsTheEasingOfTheSegment() {
        val f = fixture()
        val owner = AnimationOwner.ClipOwner(f.clipId)
        f.session.execute(SetKeyframeInterpolation(listOf(KeyframeRef(owner, prop, a.id)), Easing.EASE_IN_OUT))
        f.session.select(Selection(keyframes = setOf(KeyframeRef(owner, prop, a.id))))
        rule.waitForIdle()
        val tr = track(f)
        val (h1, _) = GraphMath.handles(a.timeUs, a.value, b.timeUs, b.value, Easing.EASE_IN_OUT)!!
        val p = pos(f, h1.timeUs.toLong(), h1.value, tr)
        rule.onNodeWithTag("graph-canvas").performTouchInput { down(p); moveBy(Offset(0f, 40f)); moveBy(Offset(0f, 20f)); up() }
        rule.waitForIdle()
        val after = track(f).byId(a.id)!!.interpolation
        assertTrue(after is Interpolation.Bezier)
        after as Interpolation.Bezier
        assertNotEquals("y1 must change", Easing.EASE_IN_OUT.y1, after.y1, 1e-6)
        assertTrue("x1 is unchanged by a vertical drag", abs(after.x1 - Easing.EASE_IN_OUT.x1) < 0.02)
        // the evaluated curve really follows the new easing
        val mid = track(f).evaluate(500_000)
        assertNotEquals(Interpolation.Linear, after)
        assertTrue("curve value at the middle is ${mid}", mid in -0.5..1.5)
    }

    @Test
    fun tappingEmptyGraphSpaceMovesThePlayheadAndPinchDoesNotEdit() {
        val f = fixture()
        val before = f.session.undoLabel
        val size = rule.onNodeWithTag("graph-canvas").fetchSemanticsNode().size
        rule.onNodeWithTag("graph-canvas").performTouchInput { click(Offset(size.width * 0.5f, size.height * 0.1f)) }
        rule.waitForIdle()
        assertTrue("playhead should move to the tapped time", f.controller.playheadUs > 1_000_000L)
        rule.onNodeWithTag("graph-canvas").performTouchInput {
            pinch(Offset(100f, 100f), Offset(80f, 100f), Offset(200f, 100f), Offset(260f, 100f))
        }
        rule.waitForIdle()
        assertEquals(before, f.session.undoLabel)
    }

    @Test
    fun valueGraphAndTimelineAreTheSameData() {
        val f = fixture()
        val owner = AnimationOwner.ClipOwner(f.clipId)
        // edit through the property panel path (session) and read back what the graph draws from
        f.session.execute(SetKeyframe(owner, prop, Keyframe(500_000, 0.25)))
        rule.waitForIdle()
        assertEquals(listOf(0L, 500_000L, 1_000_000L), track(f).keyframes.map { it.timeUs })
        assertEquals(3, id.motionlab.engine.timeline.TimelineLayout.marks(f.session.timeline, f.trackId).size)
    }
}
