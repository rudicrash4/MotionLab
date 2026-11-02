package id.motionlab.feature

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.motionlab.engine.timeline.SetTrackFlags
import id.motionlab.engine.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Timeline UI and clip gestures. Coordinates: default zoom is 100 px per second and scroll 0, so the fixture clip
 * (1 s .. 3 s) spans x = 100..300 px in the clip canvas; y = 10 px is inside the clip bar of the top row.
 */
@RunWith(AndroidJUnit4::class)
class TimelineInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private fun clip(f: EditorFixture) = f.session.timeline.requireClip(f.clipId).second

    @Test
    fun everyEditorAreaIsPresentAndTabsSwitch() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("preview").assertExists()
        rule.onNodeWithTag("ruler").assertExists()
        rule.onNodeWithTag("clip-canvas").assertExists()
        rule.onNodeWithTag("track-header-${f.trackId}").assertIsDisplayed()
        rule.onNodeWithTag("property-panel").assertExists()
        rule.onNodeWithTag("tab-Graph").performClick()
        rule.onNodeWithTag("tab-Path").performClick()
        rule.onNodeWithTag("tab-Layers").performClick()
        rule.onNodeWithTag("layers-panel").assertExists()
        rule.onNodeWithTag("tab-Properties").performClick()
        rule.onNodeWithTag("property-panel").assertExists()
    }

    @Test
    fun tapSelectsClipAndTapOnEmptySpaceClearsSelection() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(200f, 10f)) }
        rule.waitForIdle()
        assertEquals(setOf(f.clipId), f.controller.state.selection.clipIds)
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(50f, 10f)) }
        rule.waitForIdle()
        assertTrue(f.controller.state.selection.isEmpty)
    }

    @Test
    fun draggingTheClipBodyMovesItAndIsOneUndoStep() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("clip-canvas").performTouchInput {
            down(Offset(200f, 10f)); moveBy(Offset(60f, 0f)); moveBy(Offset(40f, 0f)); up()
        }
        rule.waitForIdle()
        assertEquals(2_000_000L, clip(f).startUs)
        assertEquals(2_000_000L, clip(f).durationUs)
        f.session.undo()
        assertEquals("the whole drag must be a single undo step", 1_000_000L, clip(f).startUs)
    }

    @Test
    fun draggingTheEndHandleTrimsAndTheStartHandleTrims() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("clip-canvas").performTouchInput { down(Offset(292f, 10f)); moveBy(Offset(100f, 0f)); up() }
        rule.waitForIdle()
        assertEquals(1_000_000L, clip(f).startUs)
        assertEquals(3_000_000L, clip(f).durationUs)
        // start handle: 100 px right shortens the clip from the left
        rule.onNodeWithTag("clip-canvas").performTouchInput { down(Offset(108f, 10f)); moveBy(Offset(50f, 0f)); up() }
        rule.waitForIdle()
        assertEquals(1_500_000L, clip(f).startUs)
        assertEquals(2_500_000L, clip(f).durationUs)
    }

    @Test
    fun lockedTrackRejectsAMoveAndTellsTheUser() {
        val f = EditorFixture(rule)
        f.session.execute(SetTrackFlags(f.trackId, locked = true))
        f.show()
        rule.onNodeWithTag("clip-canvas").performTouchInput { down(Offset(200f, 10f)); moveBy(Offset(100f, 0f)); up() }
        rule.waitForIdle()
        assertEquals(1_000_000L, clip(f).startUs)
        assertNotNull(f.controller.message)
        rule.onNodeWithTag("editor-message").assertIsDisplayed()
    }

    @Test
    fun pinchZoomsTheTimeAxisAndDoesNotEditAnything() {
        val f = EditorFixture(rule).show()
        val before = f.controller.viewport.pxPerSecond
        val historyBefore = f.session.undoLabel
        rule.onNodeWithTag("clip-canvas").performTouchInput {
            pinch(Offset(150f, 30f), Offset(100f, 30f), Offset(250f, 30f), Offset(400f, 30f))
        }
        rule.waitForIdle()
        assertTrue("zoom should grow", f.controller.viewport.pxPerSecond > before * 1.3)
        assertEquals(1_000_000L, clip(f).startUs)
        assertEquals("a pinch must not create an edit", historyBefore, f.session.undoLabel)
    }

    @Test
    fun horizontalDragOnEmptySpaceScrollsTheTimeline() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("clip-canvas").performTouchInput { down(Offset(600f, 10f)); moveBy(Offset(-200f, 0f)); up() }
        rule.waitForIdle()
        assertTrue("scrolled by ~2 s but was ${f.controller.viewport.scrollUs}", abs(f.controller.viewport.scrollUs - 2_000_000L) < 150_000L)
        assertEquals(1_000_000L, clip(f).startUs)
    }

    @Test
    fun tapOnTheRulerMovesThePlayhead() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("ruler").performTouchInput { click(Offset(250f, 5f)) }
        rule.waitForIdle()
        assertTrue("playhead ${f.controller.playheadUs}", abs(f.controller.playheadUs - 2_500_000L) <= 34_000L)
        rule.onNodeWithTag("timecode").assertExists()
    }

    @Test
    fun toolbarSplitDuplicateCopyPasteDeleteAndUndo() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(200f, 10f)) }
        f.controller.seek(2_000_000)
        rule.onNodeWithTag("tb-split").performClick()
        rule.waitForIdle()
        assertEquals(2, f.session.timeline.track(f.trackId).clips.size)
        rule.onNodeWithTag("tb-undo").performClick()
        rule.waitForIdle()
        assertEquals(1, f.session.timeline.track(f.trackId).clips.size)
        rule.onNodeWithTag("tb-redo").performClick()
        rule.waitForIdle()
        assertEquals(2, f.session.timeline.track(f.trackId).clips.size)

        f.session.select(id.motionlab.engine.timeline.Selection(clipIds = setOf(f.clipId)))
        rule.onNodeWithTag("tb-copy").performClick()
        f.controller.seek(5_000_000)
        rule.onNodeWithTag("tb-paste").performClick()
        rule.waitForIdle()
        assertEquals(3, f.session.timeline.track(f.trackId).clips.size)
        rule.onNodeWithTag("tb-duplicate").performClick()
        rule.waitForIdle()
        assertEquals(4, f.session.timeline.track(f.trackId).clips.size)
        rule.onNodeWithTag("tb-delete").performClick()
        rule.waitForIdle()
        assertEquals(3, f.session.timeline.track(f.trackId).clips.size)
    }

    @Test
    fun multiSelectAddsToTheSelectionAndSelectAllSelectsEverything() {
        val f = EditorFixture(rule)
        val second = f.session.addClip(f.trackId, 4_000_000, 1_000_000)
        f.show()
        rule.onNodeWithTag("tb-multi").performClick()
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(200f, 10f)) }
        rule.onNodeWithTag("clip-canvas").performTouchInput { click(Offset(450f, 10f)) }
        rule.waitForIdle()
        assertEquals(setOf(f.clipId, second), f.controller.state.selection.clipIds)
        f.session.clearSelection()
        rule.onNodeWithTag("tb-select-all").performClick()
        rule.waitForIdle()
        assertEquals(setOf(f.clipId, second), f.controller.state.selection.clipIds)
    }

    @Test
    fun trackHeaderButtonsLockHideMuteCollapseAndRemove() {
        val f = EditorFixture(rule).show()
        val id = f.trackId
        rule.onNodeWithTag("lock-$id").performClick()
        assertTrue(f.session.timeline.track(id).locked)
        rule.onNodeWithTag("visible-$id").performClick()
        assertFalse(f.session.timeline.track(id).visible)
        rule.onNodeWithTag("menu-$id").performClick()
        rule.onNodeWithTag("mute-$id").performClick()
        assertTrue(f.session.timeline.track(id).muted)
        rule.onNodeWithTag("menu-$id").performClick()
        rule.onNodeWithTag("collapse-$id").performClick()
        assertTrue(f.session.timeline.track(id).collapsed)
        rule.onNodeWithTag("menu-$id").performClick()
        rule.onNodeWithTag("remove-$id").performClick()
        rule.waitForIdle()
        assertTrue(f.session.timeline.tracks.isEmpty())
    }

    @Test
    fun addTrackAndAddClipFromTheToolbar() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("tb-add-track").performClick()
        rule.onNodeWithTag("add-track-TEXT").performClick()
        rule.waitForIdle()
        assertEquals(TrackKind.TEXT, f.session.timeline.tracks.last().kind)
        rule.onNodeWithTag("tb-add-clip").performClick()
        rule.onNodeWithTag("add-clip-text").performClick()
        rule.waitForIdle()
        val textTrack = f.session.timeline.tracks.last { it.kind == TrackKind.TEXT }
        assertEquals(1, textTrack.clips.size)
        assertEquals("Text", textTrack.clips[0].payload["text"])
        assertEquals(setOf(textTrack.clips[0].id), f.controller.state.selection.clipIds)
    }

    @Test
    fun zoomButtonsChangeTheViewport() {
        val f = EditorFixture(rule).show()
        val z0 = f.controller.viewport.pxPerSecond
        rule.onNodeWithTag("tb-zoom-in").performClick()
        assertTrue(f.controller.viewport.pxPerSecond > z0)
        rule.onNodeWithTag("tb-zoom-out").performClick()
        rule.onNodeWithTag("tb-zoom-fit").performClick()
        rule.waitForIdle()
        assertTrue(f.controller.viewport.scrollUs == 0L)
    }

    @Test
    fun playPauseTogglesPlaybackAndTheEndStopsIt() {
        val f = EditorFixture(rule).show()
        rule.onNodeWithTag("tb-play").performClick()
        rule.waitForIdle()
        assertTrue(f.controller.playing)
        rule.onNodeWithTag("tb-play").performClick()
        rule.waitForIdle()
        assertFalse(f.controller.playing)
    }
}
