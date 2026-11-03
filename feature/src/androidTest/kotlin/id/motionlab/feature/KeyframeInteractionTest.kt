package id.motionlab.feature

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.PresetLibrary
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetKeyframe
import id.motionlab.engine.timeline.TimelineLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Keyframe controls in the property panel and keyframe diamonds on the timeline. Fixture clip starts at 1 s. */
@RunWith(AndroidJUnit4::class)
class KeyframeInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private val prop = PropertyNames.OPACITY

    private fun owner(f: EditorFixture) = AnimationOwner.ClipOwner(f.clipId)

    private fun track(f: EditorFixture) = f.session.anim(owner(f)).properties.track(prop)

    private fun key(f: EditorFixture, localUs: Long, value: Double, i: Interpolation = Interpolation.Linear): Keyframe {
        val k = Keyframe(localUs, value, i)
        f.session.execute(SetKeyframe(owner(f), prop, k))
        return k
    }

    private fun selectClip(f: EditorFixture) = f.session.select(Selection(clipIds = setOf(f.clipId)))

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun stopwatchAddsAndRemovesAKeyframeAtThePlayhead() {
        val f = EditorFixture(rule)
        selectClip(f)
        f.show()
        f.controller.seek(1_500_000) // clip-local 0.5 s
        rule.waitForIdle()
        click("kf-toggle-opacity")
        assertEquals(listOf(500_000L), track(f).keyframes.map { it.timeUs })
        click("kf-toggle-opacity")
        assertTrue(track(f).isEmpty)
    }

    @Test
    fun editingAnAnimatedPropertyCreatesAKeyframeAtThePlayhead() {
        val f = EditorFixture(rule)
        key(f, 0, 1.0)
        selectClip(f)
        f.show()
        f.controller.seek(2_000_000) // local 1 s
        rule.waitForIdle()
        click("dec-opacity")
        val t = track(f)
        assertEquals(listOf(0L, 1_000_000L), t.keyframes.map { it.timeUs })
        assertEquals(0.99, t.at(1_000_000)!!.value, 1e-6)
    }

    @Test
    fun editingAConstantPropertyDoesNotCreateKeyframes() {
        val f = EditorFixture(rule)
        selectClip(f)
        f.show()
        click("dec-opacity")
        assertTrue(track(f).isEmpty)
        assertEquals(0.99, f.session.valueAtPlayhead(owner(f), prop), 1e-6)
    }

    @Test
    fun clearingAnimationKeepsTheValueAtThePlayhead() {
        val f = EditorFixture(rule)
        key(f, 0, 1.0); key(f, 1_000_000, 0.0)
        selectClip(f)
        f.show()
        f.controller.seek(1_500_000) // local 0.5 -> 0.5
        rule.waitForIdle()
        click("kf-clear-opacity")
        assertTrue(track(f).isEmpty)
        assertEquals(0.5, f.session.valueAtPlayhead(owner(f), prop), 1e-6)
    }

    @Test
    fun presetWritesOrdinaryKeyframesThatShowOnTheTimeline() {
        val f = EditorFixture(rule)
        selectClip(f)
        f.show()
        val preset = PresetLibrary.all().first()
        rule.onNodeWithTag("presets-button").performScrollTo().performClick()
        rule.onNodeWithTag("preset-${preset.id}").performClick()
        rule.waitForIdle()
        val anim = f.session.anim(owner(f))
        assertTrue("preset must create keyframes", anim.properties.tracks.values.any { !it.isEmpty })
        assertEquals(preset.id, anim.presets.single().presetId)
        assertTrue("and they are visible as diamonds", TimelineLayout.marks(f.session.timeline, f.trackId).isNotEmpty())
        f.session.undo()
        assertTrue("one undo removes the whole preset", f.session.anim(owner(f)).properties.tracks.values.all { it.isEmpty })
    }

    @Test
    fun tappingADiamondSelectsItAndDraggingMovesItInTime() {
        val f = EditorFixture(rule)
        key(f, 0, 1.0)
        val moving = key(f, 500_000, 0.5) // absolute 1.5 s -> x = 150 px
        f.show()
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(150f, 62.dp.toPx())) }
        rule.waitForIdle()
        assertEquals(setOf(KeyframeRef(owner(f), prop, moving.id)), f.controller.state.selection.keyframes)
        rule.onNodeWithTag("clip-canvas").performTouchInput {
            down(Offset(150f, 62.dp.toPx())); moveBy(Offset(30f, 0f)); moveBy(Offset(20f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals(listOf(0L, 1_000_000L), track(f).keyframes.map { it.timeUs })
        assertEquals("stable id survives the move", moving.id, track(f).at(1_000_000)!!.id)
        f.session.undo()
        assertEquals("the drag is one undo step", listOf(0L, 500_000L), track(f).keyframes.map { it.timeUs })
    }

    @Test
    fun interpolationMenuChangesTheSelectedKeyframe() {
        val f = EditorFixture(rule)
        val k = key(f, 0, 1.0); key(f, 1_000_000, 0.0)
        f.session.select(Selection(keyframes = setOf(KeyframeRef(owner(f), prop, k.id))))
        f.show()
        click("interp-button")
        rule.onNodeWithTag("interp-HOLD").performClick()
        rule.waitForIdle()
        assertEquals(Interpolation.Hold, track(f).byId(k.id)!!.interpolation)
        click("interp-button")
        rule.onNodeWithTag("interp-EASE_IN_OUT").performClick()
        rule.waitForIdle()
        assertTrue(track(f).byId(k.id)!!.interpolation is Interpolation.Bezier)
    }

    @Test
    fun keyframeValueFieldAndDeleteButtonWork() {
        val f = EditorFixture(rule)
        val k = key(f, 0, 1.0)
        f.session.select(Selection(keyframes = setOf(KeyframeRef(owner(f), prop, k.id))))
        f.show()
        click("kf-delete")
        assertTrue(track(f).isEmpty)
        assertNotNull(f.controller.state)
    }

    @Test
    fun previousAndNextKeyframeButtonsMoveThePlayhead() {
        val f = EditorFixture(rule)
        key(f, 0, 1.0); key(f, 500_000, 0.5); key(f, 1_000_000, 0.0)
        selectClip(f)
        f.show()
        f.controller.seek(1_000_000)
        rule.onNodeWithTag("tb-key-next").performClick(); rule.waitForIdle()
        assertEquals(1_500_000L, f.controller.playheadUs)
        rule.onNodeWithTag("tb-key-next").performClick(); rule.waitForIdle()
        assertEquals(2_000_000L, f.controller.playheadUs)
        rule.onNodeWithTag("tb-key-prev").performClick(); rule.waitForIdle()
        assertEquals(1_500_000L, f.controller.playheadUs)
    }

    @Test
    fun copyPasteAndDuplicateKeyframes() {
        val f = EditorFixture(rule)
        val k = key(f, 500_000, 0.5)
        f.session.select(Selection(keyframes = setOf(KeyframeRef(owner(f), prop, k.id))))
        f.show()
        rule.onNodeWithTag("tb-more").performClick()
        rule.onNodeWithTag("tb-copy").performClick()
        f.controller.seek(2_000_000) // local 1 s
        rule.onNodeWithTag("tb-more").performClick()
        rule.onNodeWithTag("tb-paste").performClick()
        rule.waitForIdle()
        assertEquals(listOf(500_000L, 1_000_000L), track(f).keyframes.map { it.timeUs })
        assertTrue("copy has a new id", track(f).at(1_000_000)!!.id != k.id)
        f.session.select(Selection(keyframes = setOf(KeyframeRef(owner(f), prop, k.id))))
        rule.onNodeWithTag("tb-more").performClick()
        rule.onNodeWithTag("tb-duplicate").performClick()
        rule.waitForIdle()
        assertEquals(3, track(f).keyframes.size)
    }

    @Test
    fun multiSelectedKeyframesMoveTogether() {
        val f = EditorFixture(rule)
        val a = key(f, 500_000, 0.5)
        f.session.execute(SetKeyframe(owner(f), PropertyNames.ROTATION, Keyframe(500_000, 10.0)))
        val b = f.session.anim(owner(f)).properties.track(PropertyNames.ROTATION).at(500_000)!!
        f.show()
        // both properties' keyframes at the same time form ONE diamond: tapping selects both
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(150f, 62.dp.toPx())) }
        rule.waitForIdle()
        assertEquals(
            setOf(KeyframeRef(owner(f), prop, a.id), KeyframeRef(owner(f), PropertyNames.ROTATION, b.id)),
            f.controller.state.selection.keyframes,
        )
        rule.onNodeWithTag("clip-canvas").performTouchInput { down(Offset(150f, 62.dp.toPx())); moveBy(Offset(50f, 0f)); up() }
        rule.waitForIdle()
        assertEquals(1_000_000L, track(f).keyframes.single().timeUs)
        assertEquals(1_000_000L, f.session.anim(owner(f)).properties.track(PropertyNames.ROTATION).keyframes.single().timeUs)
    }
}
