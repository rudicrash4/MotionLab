package id.motionlab.feature

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.MediaAccessState
import id.motionlab.core.resource.MediaType
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.media.AssetImporter
import id.motionlab.engine.media.DeviceMedia
import id.motionlab.engine.media.ImportOutcome
import id.motionlab.engine.timeline.PreviewMath
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetTrackFlags
import id.motionlab.engine.timeline.TrackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Phase 3.1 UI: Add Media flow, track choice, permission states, square preview and touch mapping. */
@RunWith(AndroidJUnit4::class)
class Phase31UiTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(context.cacheDir, "ui31").also { it.deleteRecursively(); it.mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun importFile(f: File, kind: ResourceKind): AssetRefDto {
        val out = AssetImporter(context).import(Uri.fromFile(f), setOf(kind))
        return (out as ImportOutcome.Imported).asset
    }

    private fun shown(): EditorFixture {
        val f = EditorFixture(rule).show()
        return f
    }

    private fun openMedia(cat: String) {
        rule.onNodeWithTag("tb-add-media").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("media-library").assertIsDisplayed() }.isSuccess }
        rule.onNodeWithTag("media-cat-$cat").performClick()
    }

    // ------------------------------------------------------------------ insertion UX

    @Test fun addMediaButtonOpensTheMediaPanelAndAddToTimelineInsertsAtThePlayhead() {
        val f = shown()
        val a = importFile(TestMedia.png(dir, "p.png", 300, 150), ResourceKind.IMAGE)
        f.session.putAssets(listOf(a))
        f.controller.seek(2_000_000L)
        rule.waitForIdle()
        openMedia("IMAGES")
        rule.onNodeWithTag("media-add-selected").assertIsNotEnabled() // nothing selected yet
        rule.onNodeWithTag("media-row-${a.id}").performClick()
        rule.onNodeWithTag("media-add-selected").assertIsEnabled().performClick()
        rule.waitForIdle()
        val track = f.session.timeline.tracks.single { it.kind == TrackKind.IMAGE }
        val clip = track.clips.single()
        assertEquals(a.id, clip.assetId)
        assertEquals(2_000_000L, clip.startUs)
        assertEquals(setOf(clip.id), f.session.selection.clipIds)
    }

    @Test fun quickAddFromTheRowInsertsAudioOnAnAudioTrack() {
        val f = shown()
        val a = importFile(TestMedia.wav(dir, "t.wav", 2), ResourceKind.AUDIO)
        f.session.putAssets(listOf(a))
        rule.waitForIdle()
        openMedia("AUDIO")
        rule.onNodeWithTag("media-add-${a.id}").performClick()
        rule.waitForIdle()
        val (track, clip) = f.session.timeline.tracks.first { it.kind == TrackKind.AUDIO }.let { it to it.clips.single() }
        assertEquals(TrackKind.AUDIO, track.kind)
        assertEquals(a.id, clip.assetId)
        assertEquals(setOf(clip.id), f.session.selection.clipIds)
    }

    @Test fun clipFarFromTheVisibleRangeIsScrolledIntoView() {
        val f = shown()
        val a = importFile(TestMedia.png(dir, "far.png", 100, 100), ResourceKind.IMAGE)
        f.session.putAssets(listOf(a))
        f.controller.seek(120_000_000L)
        rule.waitForIdle()
        openMedia("IMAGES")
        rule.onNodeWithTag("media-add-${a.id}").performClick()
        rule.waitForIdle()
        assertTrue("the new clip's start must be on screen", f.controller.viewport.showsClipStart(120_000_000L, f.controller.timelineWidthPx))
    }

    @Test fun lockedSelectedTrackIsSkippedAndExplained() {
        val f = shown()
        val a = importFile(TestMedia.png(dir, "l.png", 100, 100), ResourceKind.IMAGE)
        f.session.putAssets(listOf(a))
        f.session.addAssetClip(a.id, 0)
        val imageTrack = f.session.timeline.tracks.single { it.kind == TrackKind.IMAGE }
        f.session.execute(SetTrackFlags(imageTrack.id, locked = true))
        f.session.select(Selection(clipIds = setOf(imageTrack.clips.single().id)))
        f.controller.seek(10_000_000L)
        rule.waitForIdle()
        openMedia("IMAGES")
        rule.onNodeWithTag("media-add-${a.id}").performClick()
        rule.waitForIdle()
        val tracks = f.session.timeline.tracks.filter { it.kind == TrackKind.IMAGE }
        assertEquals("a new, unlocked image track was created", 2, tracks.size)
        assertTrue(tracks.first { it.id != imageTrack.id }.clips.size == 1)
        assertEquals("locked track untouched", 1, tracks.first { it.id == imageTrack.id }.clips.size)
        rule.onNodeWithTag("media-status").assertIsDisplayed()
    }

    @Test fun secondImageAtTheSameTimeIsStackedOnItsOwnTrackNotPushedAway() {
        val f = shown()
        val a = importFile(TestMedia.png(dir, "s.png", 100, 100), ResourceKind.IMAGE)
        f.session.putAssets(listOf(a))
        rule.waitForIdle()
        openMedia("IMAGES")
        rule.onNodeWithTag("media-add-${a.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("media-add-${a.id}").performClick()
        rule.waitForIdle()
        val imageTracks = f.session.timeline.tracks.filter { it.kind == TrackKind.IMAGE }
        assertEquals(2, imageTracks.size)
        assertTrue(imageTracks.all { t -> t.clips.single().startUs == 0L })
    }

    // ------------------------------------------------------------------ permissions UI (state shown through accessOverride)

    private fun showDevicePanel(state: MediaAccessState): EditorFixture {
        val f = EditorFixture(rule)
        val lib = MediaLibraryController(context, f.session, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        lib.source = LibrarySource.DEVICE
        lib.selectCategory(LibraryCategory.IMAGES)
        rule.setContent { MaterialTheme { Column(Modifier.height(500.dp)) { MediaLibraryPanel(lib, f.controller, accessOverride = state) } } }
        rule.waitForIdle()
        return f
    }

    @Test fun beforeAskingTheRationaleAndBothChoicesAreShown() {
        showDevicePanel(MediaAccessState.NOT_ASKED)
        rule.onNodeWithTag("perm-rationale").assertIsDisplayed()
        rule.onNodeWithTag("perm-allow").assertIsDisplayed()
        rule.onNodeWithTag("perm-fallback-picker").assertIsDisplayed() // Photo Picker / SAF stays available
        rule.onNodeWithTag("perm-settings").assertDoesNotExist()
    }

    @Test fun deniedShowsTheReasonTryAgainAndTheFallback() {
        showDevicePanel(MediaAccessState.DENIED)
        rule.onNodeWithTag("perm-denied").assertIsDisplayed()
        rule.onNodeWithTag("perm-allow").assertIsDisplayed()
        rule.onNodeWithTag("perm-fallback-picker").assertIsDisplayed()
    }

    @Test fun permanentlyDeniedExplainsSettingsAndDoesNotOfferAnotherDialog() {
        showDevicePanel(MediaAccessState.PERMANENTLY_DENIED)
        rule.onNodeWithTag("perm-settings-text").assertIsDisplayed()
        rule.onNodeWithTag("perm-settings").assertIsDisplayed()
        rule.onNodeWithTag("perm-fallback-picker").assertIsDisplayed()
        rule.onNodeWithTag("perm-allow").assertDoesNotExist() // no repeated prompts
    }

    @Test fun partialAccessListsTheSharedMediaAndOffersAllowAll() {
        showDevicePanel(MediaAccessState.PARTIAL)
        rule.onNodeWithTag("perm-partial").assertIsDisplayed()
        rule.onNodeWithTag("perm-allow").assertIsDisplayed()
        rule.onNodeWithTag("device-browser").assertExists()
    }

    @Test fun fullAccessShowsTheBrowserWithoutAnyPermissionUi() {
        showDevicePanel(MediaAccessState.FULL)
        rule.onNodeWithTag("device-browser").assertExists()
        rule.onNodeWithTag("perm-allow").assertDoesNotExist()
        rule.onNodeWithTag("perm-rationale").assertDoesNotExist()
    }

    @Test fun deviceItemsAreNotDuplicatedAgainstProjectAssets() {
        val f = EditorFixture(rule)
        val a = importFile(TestMedia.png(dir, "same.png", 40, 40), ResourceKind.IMAGE)
        f.session.putAssets(listOf(a))
        val lib = MediaLibraryController(context, f.session, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        try {
            assertEquals(a.id, lib.assetFor(DeviceMedia(a.uri, MediaType.IMAGES, "same.png", 0, 40, 40, a.sizeBytes, 0, "image/png"))?.id)
            assertEquals(null, lib.assetFor(DeviceMedia("content://media/external/images/media/999999", MediaType.IMAGES, "other.png", 0, 1, 1, 1, 0, null)))
        } finally { lib.dispose() }
    }

    // ------------------------------------------------------------------ square preview + touch mapping

    @Test fun previewViewportIsSquare() {
        shown()
        val size = rule.onNodeWithTag("preview-square").fetchSemanticsNode().size
        assertTrue("square: ${size.width} x ${size.height}", abs(size.width - size.height) <= 1)
        val overlay = rule.onNodeWithTag("preview-overlay").fetchSemanticsNode().size
        assertEquals("the touch overlay covers exactly the preview", size.width, overlay.width)
        assertEquals(size.height, overlay.height)
    }

    @Test fun tappingTheClipCentreSelectsItAndTappingEmptySpaceDeselects() {
        val f = shown()
        f.controller.seek(1_500_000L) // the fixture clip (1 s .. 3 s) is on screen at the canvas centre
        f.session.clearSelection()
        rule.waitForIdle()
        rule.onNodeWithTag("preview-overlay").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(setOf(f.clipId), f.session.selection.clipIds)
        // the canvas is letterboxed inside the square: the corner of the square lies outside any clip
        rule.onNodeWithTag("preview-overlay").performTouchInput { click(Offset(3f, 3f)) }
        rule.waitForIdle()
        assertTrue(f.session.selection.clipIds.isEmpty())
    }

    @Test fun mappingMatchesTheRendererLetterboxForTheProjectCanvas() {
        val f = shown()
        val size = rule.onNodeWithTag("preview-overlay").fetchSemanticsNode().size
        val vm = PreviewMath.viewMap(size.width, size.height, f.session.canvasWidth, f.session.canvasHeight)
        val box = PreviewMath.letterbox(size.width, size.height, f.session.canvasWidth, f.session.canvasHeight)
        assertEquals(box[0].toDouble(), vm.toScreenX(-f.session.canvasWidth / 2.0), 0.5)
        assertEquals(box[1].toDouble(), vm.toScreenY(-f.session.canvasHeight / 2.0), 0.5)
        val canvasAspect = f.session.canvasWidth.toDouble() / f.session.canvasHeight
        assertEquals("no distortion", canvasAspect, box[2].toDouble() / box[3], 0.02)
    }
}
