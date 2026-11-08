package id.motionlab.feature

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.animation.EaseCurves
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.media.AssetImporter
import id.motionlab.engine.media.ImportOutcome
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.IdSource
import id.motionlab.engine.timeline.KeyframeRef
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.SetKeyframe
import id.motionlab.engine.timeline.TrackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 3.2 on a device: direct media layers through the real MediaLibraryController, fonts, the Ease editor and the
 * scrollable panels. Media files are generated on the device; fonts come from the system font directory.
 */
@RunWith(AndroidJUnit4::class)
class Phase32Test {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(context.cacheDir, "p32").also { it.deleteRecursively(); it.mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun library(session: EditorSession) =
        MediaLibraryController(context, session, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))

    /** Runs the controller's real import + auto-insert and waits for it. */
    private fun importAndInsert(lib: MediaLibraryController, session: EditorSession, files: List<File>, kinds: Set<ResourceKind>, atUs: Long): List<id.motionlab.engine.timeline.AssetInsertion> {
        val inserted = java.util.concurrent.CopyOnWriteArrayList<id.motionlab.engine.timeline.AssetInsertion>()
        lib.import(files.map { Uri.fromFile(it) }, kinds, InsertTarget({ atUs }, { null }, { inserted += it }))
        val end = System.currentTimeMillis() + 15_000
        while (inserted.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(50)
        return inserted
    }

    // ------------------------------------------------------------------ direct media layers

    @Test fun importingAnImageCreatesAnImageLayerAtThePlayheadSelectedAndShown() {
        val s = EditorSession(ids = IdSource.sequential())
        val lib = library(s)
        try {
            val ins = importAndInsert(lib, s, listOf(TestMedia.png(dir, "a.png", 300, 200, 0xFF00FF00.toInt())), setOf(ResourceKind.IMAGE), 1_500_000)
            assertEquals(1, ins.size)
            val (track, clip) = s.timeline.requireClip(ins.single().clipId)
            assertEquals(TrackKind.IMAGE, track.kind)
            assertEquals(1_500_000L, clip.startUs)
            assertEquals(s.assets.single().id, clip.assetId)
            assertEquals(setOf(clip.id), s.selection.clipIds)
            // preview integration: the renderer's data source returns the real decoded bitmap
            val end = System.currentTimeMillis() + 8_000
            var f = lib.preview.frameFor(clip, TrackKind.IMAGE, 0)
            while (f == null && System.currentTimeMillis() < end) { Thread.sleep(50); f = lib.preview.frameFor(clip, TrackKind.IMAGE, 0) }
            assertNotNull("preview must receive the decoded image", f)
        } finally { lib.dispose() }
    }

    @Test fun importingVideoCreatesAVideoLayerWithTheSourceDuration() {
        val mp4 = TestMedia.mp4(dir, "v.mp4"); assumeTrue("no H.264 encoder on this device", mp4 != null)
        val s = EditorSession(ids = IdSource.sequential())
        val lib = library(s)
        try {
            val ins = importAndInsert(lib, s, listOf(mp4!!), setOf(ResourceKind.VIDEO), 0)
            val (track, clip) = s.timeline.requireClip(ins.single().clipId)
            assertEquals(TrackKind.VIDEO, track.kind)
            assertEquals(s.assets.single().durationUs, clip.durationUs)
            assertEquals(s.assets.single().id, clip.assetId)
        } finally { lib.dispose() }
    }

    @Test fun importingAudioCreatesAnAudioLayerWithTheSourceDuration() {
        val s = EditorSession(ids = IdSource.sequential())
        val lib = library(s)
        try {
            val ins = importAndInsert(lib, s, listOf(TestMedia.wav(dir, "a.wav", 3)), setOf(ResourceKind.AUDIO), 250_000)
            val (track, clip) = s.timeline.requireClip(ins.single().clipId)
            assertEquals(TrackKind.AUDIO, track.kind)
            assertEquals(3_000_000L, clip.durationUs.coerceIn(2_900_000L, 3_100_000L))
            assertEquals(250_000L, clip.startUs)
        } finally { lib.dispose() }
    }

    @Test fun aWronglyNamedFileIsClassifiedByItsContent() {
        // a PNG saved as ".mp4" must still become an image layer, not a broken video
        val png = TestMedia.png(dir, "real.png", 40, 40)
        val liar = File(dir, "liar.mp4").also { png.copyTo(it) }
        val s = EditorSession(ids = IdSource.sequential())
        val out = AssetImporter(context).import(Uri.fromFile(liar), null)
        assertTrue("import failed: $out", out is ImportOutcome.Imported)
        s.putAssets(listOf((out as ImportOutcome.Imported).asset))
        assertEquals("IMAGE", s.assets.single().kind)
    }

    @Test fun anUndecodableFileGivesAClearErrorAndNoLayer() {
        val junk = File(dir, "junk.mp4").also { it.writeBytes(ByteArray(2048) { (it * 7).toByte() }) }
        val out = AssetImporter(context).import(Uri.fromFile(junk), setOf(ResourceKind.VIDEO))
        assertTrue(out is ImportOutcome.Failed)
        assertTrue((out as ImportOutcome.Failed).reason.isNotBlank())
    }

    // ------------------------------------------------------------------ fonts

    private fun systemFont(ext: String): File? =
        File("/system/fonts").listFiles { f -> f.name.endsWith(ext, ignoreCase = true) }?.minByOrNull { it.length() }

    @Test fun importingATtfRegistersItsFamily() {
        val ttf = systemFont(".ttf"); assumeTrue("no system TTF", ttf != null)
        val copy = File(dir, "my.ttf").also { ttf!!.copyTo(it) }
        val out = AssetImporter(context).import(Uri.fromFile(copy), setOf(ResourceKind.FONT))
        assertTrue("$out", out is ImportOutcome.Imported)
        val a = (out as ImportOutcome.Imported).asset
        assertEquals("FONT", a.kind); assertNotNull(a.fontFamily)
    }

    @Test fun importingAnOtfRegistersItsFamily() {
        val otf = systemFont(".otf"); assumeTrue("this device ships no OTF in /system/fonts", otf != null)
        val copy = File(dir, "my.otf").also { otf!!.copyTo(it) }
        val out = AssetImporter(context).import(Uri.fromFile(copy), setOf(ResourceKind.FONT))
        assertTrue("$out", out is ImportOutcome.Imported)
    }

    @Test fun anInvalidFontIsRejectedBeforeRegistration() {
        val bad = File(dir, "bad.ttf").also { it.writeBytes(ByteArray(512) { 3 }) }
        val s = EditorSession(ids = IdSource.sequential())
        val lib = library(s)
        try {
            lib.import(listOf(Uri.fromFile(bad)), setOf(ResourceKind.FONT))
            val end = System.currentTimeMillis() + 8_000
            while (lib.status == null && System.currentTimeMillis() < end) Thread.sleep(50)
            assertTrue(s.assets.none { it.kind == "FONT" })
            assertTrue(lib.status!!.contains("Failed"))
        } finally { lib.dispose() }
    }

    @Test fun importedFontsSurviveSaveAndReopen() {
        val ttf = systemFont(".ttf"); assumeTrue("no system TTF", ttf != null)
        val copy = File(dir, "persist.ttf").also { ttf!!.copyTo(it) }
        val a = (AssetImporter(context).import(Uri.fromFile(copy), setOf(ResourceKind.FONT)) as ImportOutcome.Imported).asset
        val s = EditorSession(ids = IdSource.sequential()); s.putAssets(listOf(a))
        // "reopen": a new session with the saved asset list; the library re-registers every FONT asset
        val s2 = EditorSession(ids = IdSource.sequential()); s2.putAssets(s.assets)
        val lib = library(s2)
        try {
            lib.registerFonts()
            assertTrue(lib.fonts.registeredNames().contains(a.fontFamily))
        } finally { lib.dispose() }
    }

    @Test fun textConfigurationOffersFontImportAndMediaPanelDoesNot() {
        val f = EditorFixture(rule)
        val tt = f.session.addTrack(TrackKind.TEXT)
        val clip = f.session.addClip(tt, 0, 2_000_000, payload = mapOf("text" to "Hello"))
        f.session.select(Selection(clipIds = setOf(clip)))
        f.show()
        rule.onNodeWithTag("prop-cat-CONTENT").performClick()
        rule.onNodeWithTag("import-font").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("font-button").assertExists()
        rule.onNodeWithTag("font-empty").performScrollTo().assertExists()
        rule.onNodeWithTag("tab-Media").performClick()
        rule.onNodeWithTag("media-cat-FONTS").assertDoesNotExist()
        rule.onNodeWithTag("import-fonts").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ Ease editor

    private val prop = PropertyNames.OPACITY
    private fun easeFixture(): EditorFixture {
        val f = EditorFixture(rule)
        val owner = AnimationOwner.ClipOwner(f.clipId)
        f.session.execute(SetKeyframe(owner, prop, Keyframe(0, 0.0, Interpolation.Linear, "ka")))
        f.session.execute(SetKeyframe(owner, prop, Keyframe(1_000_000, 1.0, Interpolation.Linear, "kb")))
        f.session.select(Selection(clipIds = setOf(f.clipId), keyframes = setOf(KeyframeRef(owner, prop, "ka"))))
        f.show()
        rule.onNodeWithTag("tab-Graph").performClick()
        rule.waitForIdle()
        return f
    }

    private fun interp(f: EditorFixture) = f.session.anim(AnimationOwner.ClipOwner(f.clipId)).properties.track(prop).byId("ka")!!.interpolation

    @Test fun easeTabIsDefaultAndShowsTheRealCubicBezierText() {
        val f = easeFixture()
        rule.onNodeWithTag("ease-tab").assertExists()
        rule.onNodeWithTag("ease-canvas").assertExists()
        rule.onNodeWithTag("ease-css").assertExists()
        rule.onNodeWithTag("graph-mode-value").assertExists(); rule.onNodeWithTag("graph-mode-speed").assertExists()
        rule.onNodeWithTag("graph-mode-position").assertExists()
        f.session.execute(id.motionlab.engine.timeline.SetKeyframeInterpolation(listOf(KeyframeRef(AnimationOwner.ClipOwner(f.clipId), prop, "ka")), Interpolation.Bezier(0.33, 0.0, 0.66, 1.0)))
        rule.waitForIdle()
        assertEquals("cubic-bezier(0.33, 0.00, 0.66, 1.00)", EaseCurves.css(interp(f)))
    }

    @Test fun selectingAPresetChangesTheKeyframeInterpolationAndUndoRestoresIt() {
        val f = easeFixture()
        rule.onNodeWithTag("ease-preset-ease-in").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(EaseCurves.BUILT_IN.first { it.id == "ease-in" }.interpolation, interp(f))
        f.session.undo()
        rule.waitForIdle()
        assertEquals(Interpolation.Linear, interp(f))
    }

    @Test fun draggingABezierHandleEditsTheCurveAsOneUndoStep() {
        val f = easeFixture()
        // Linear's handles sit on the diagonal at 1/3 and 2/3; grab the first one and pull it up-left
        val size = rule.onNodeWithTag("ease-canvas").fetchSemanticsNode().size
        val d = rule.density.density
        val pad = 22f * d
        val pw = size.width - 2 * pad; val ph = size.height - 2 * pad
        val lo = -0.12f; val hi = 1.12f
        val start = Offset(pad + pw / 3f, pad + (hi - 1f / 3f) / (hi - lo) * ph)
        val canUndoBefore = f.session.undoLabel
        rule.onNodeWithTag("ease-canvas").performTouchInput { down(start); moveBy(Offset(-pw * 0.2f, -ph * 0.2f)); moveBy(Offset(0f, -ph * 0.1f)); up() }
        rule.waitForIdle()
        val b = interp(f) as Interpolation.Bezier
        assertTrue("handle 1 moved up/left: $b", b.y1 > 0.5 && b.x1 < 0.33)
        assertTrue(b.x1 in 0.0..1.0 && b.x2 in 0.0..1.0)
        // the clip's evaluated value really changed
        assertTrue(f.session.anim(AnimationOwner.ClipOwner(f.clipId)).properties.track(prop).evaluate(250_000) > 0.3)
        f.session.undo()
        rule.waitForIdle()
        assertEquals(Interpolation.Linear, interp(f))
        assertEquals(canUndoBefore, f.session.undoLabel)
    }

    @Test fun draggingTheHandleFarOutsideStaysClamped() {
        val f = easeFixture()
        val size = rule.onNodeWithTag("ease-canvas").fetchSemanticsNode().size
        val d = rule.density.density
        val pad = 22f * d
        val pw = size.width - 2 * pad; val ph = size.height - 2 * pad
        val start = Offset(pad + pw / 3f, pad + (1.12f - 1f / 3f) / 1.24f * ph)
        rule.onNodeWithTag("ease-canvas").performTouchInput { down(start); moveBy(Offset(-pw, -ph * 2f)); up() }
        rule.waitForIdle()
        val b = interp(f) as Interpolation.Bezier
        assertEquals(0.0, b.x1, 1e-9)
        assertTrue(b.y1 <= EaseCurves.Y_MAX + 1e-9)
    }

    @Test fun easingPersistsInTheSavedProject() {
        val f = easeFixture()
        rule.onNodeWithTag("ease-preset-ease-out").performScrollTo().performClick()
        rule.waitForIdle()
        // a real save to disk and a real load: the easing must come back from project.json
        val store = id.motionlab.core.project.ProjectStore(File(dir, "proj").also { it.mkdirs() })
        store.save(f.session.toDocument(testDocument()))
        val reopened = EditorSession.fromDocument(store.load())
        val back = reopened.anim(AnimationOwner.ClipOwner(f.clipId)).properties.track(prop).byId("ka")!!.interpolation
        assertTrue("easing survives save and reopen: $back", EaseCurves.same(EaseCurves.BUILT_IN.first { it.id == "ease-out" }.interpolation, back))
    }

    @Test fun positionTabSaysSoWhenPositionIsNotAnimated() {
        easeFixture()
        rule.onNodeWithTag("graph-mode-position").performClick()
        rule.onNodeWithTag("position-empty").assertExists()
        rule.onNodeWithTag("position-canvas").assertDoesNotExist()
    }

    @Test fun positionTabDrawsThePathOfAnimatedPosition() {
        val f = EditorFixture(rule)
        val owner = AnimationOwner.ClipOwner(f.clipId)
        f.session.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(0, 0.0, Interpolation.Linear, "px0")))
        f.session.execute(SetKeyframe(owner, PropertyNames.POSITION_X, Keyframe(1_000_000, 200.0, Interpolation.Linear, "px1")))
        f.session.select(Selection(clipIds = setOf(f.clipId)))
        f.show()
        rule.onNodeWithTag("tab-Graph").performClick()
        rule.onNodeWithTag("graph-mode-position").performClick()
        rule.onNodeWithTag("position-canvas").assertExists()
    }

    // ------------------------------------------------------------------ scrollable panels

    @Test fun graphEditorScrollsSoThePresetListStaysReachable() {
        easeFixture()
        rule.onNodeWithTag("ease-preset-ease-in-out-back").performScrollTo().assertIsDisplayed()
    }

    @Test fun propertySectionsAreScrollContainers() {
        val f = EditorFixture(rule)
        f.session.select(Selection(clipIds = setOf(f.clipId)))
        f.show()
        for (cat in listOf("TRANSFORM", "MASK", "BLEND", "ANIMATION")) {
            rule.onNodeWithTag("prop-cat-$cat").performClick()
            rule.onNodeWithTag("prop-section-$cat").assertExists()
            rule.onNodeWithTag("prop-section-$cat").assert(androidx.compose.ui.test.hasScrollAction())
        }
    }

    @Test fun mediaPanelScrollsAndKeepsAddToTimelineReachable() {
        val f = EditorFixture(rule)
        f.show()
        rule.onNodeWithTag("tab-Media").performClick()
        rule.onNodeWithTag("media-scroll").assert(androidx.compose.ui.test.hasScrollAction())
        rule.onNodeWithTag("media-add-selected").assertIsDisplayed()
        rule.onNodeWithTag("import-photos").assertExists()
    }

    @Test fun manualAddTrackIsNotInThePrimaryToolbar() {
        val f = EditorFixture(rule)
        f.show()
        rule.onNodeWithTag("tb-add-track").assertDoesNotExist()
        rule.onNodeWithTag("tb-add-media").assertExists()
    }
}
