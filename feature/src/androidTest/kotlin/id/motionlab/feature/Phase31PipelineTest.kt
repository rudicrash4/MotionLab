package id.motionlab.feature

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.media.AssetImporter
import id.motionlab.engine.media.BitmapProvider
import id.motionlab.engine.media.ImportOutcome
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.IdSource
import id.motionlab.engine.timeline.TrackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 3.1: the media pipeline from a real file to the pixels the preview asks for:
 * file -> AssetImporter -> AssetRefDto -> EditorSession.putAssets -> insertAsset -> MediaClip.assetId -> PreviewMedia.
 * Files are generated on the device. The final GL draw is not pixel-tested here: the test checks that the renderer's data
 * source (PreviewMedia.frameFor) returns the real decoded bitmap.
 */
@RunWith(AndroidJUnit4::class)
class Phase31PipelineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var importer: AssetImporter
    private lateinit var session: EditorSession

    @Before fun setUp() {
        dir = File(context.cacheDir, "pipeline-test").also { it.deleteRecursively(); it.mkdirs() }
        importer = AssetImporter(context)
        session = EditorSession(ids = IdSource.sequential())
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun import(file: File, kind: ResourceKind): AssetRefDto {
        val out = importer.import(Uri.fromFile(file), setOf(kind))
        assertTrue("import failed: $out", out is ImportOutcome.Imported)
        val a = (out as ImportOutcome.Imported).asset
        session.putAssets(listOf(a))
        return a
    }

    private fun waitFrame(media: PreviewMedia, clip: id.motionlab.engine.timeline.MediaClip, kind: TrackKind, localUs: Long) =
        run {
            val end = System.currentTimeMillis() + 8_000
            var f = media.frameFor(clip, kind, localUs)
            while (f == null && System.currentTimeMillis() < end) { Thread.sleep(50); f = media.frameFor(clip, kind, localUs) }
            f
        }

    @Test fun importedImageBecomesAnImageClipAndPreviewReturnsItsRealPixels() {
        val a = import(TestMedia.png(dir, "photo.png", 400, 200, 0xFFFF0000.toInt()), ResourceKind.IMAGE)
        val ins = session.insertAsset(a.id, 2_000_000L)
        val (track, clip) = session.timeline.requireClip(ins.clipId)
        assertEquals(TrackKind.IMAGE, track.kind)
        assertEquals(a.id, clip.assetId)
        assertEquals(2_000_000L, clip.startUs)
        assertEquals(5_000_000L, clip.durationUs) // images default to 5 s
        assertEquals(setOf(clip.id), session.selection.clipIds)
        // payload keeps the media's own aspect (2:1), not the square viewport
        assertEquals(2.0, clip.payload["w"]!!.toDouble() / clip.payload["h"]!!.toDouble(), 0.02)

        val media = PreviewMedia(session, BitmapProvider(context))
        try {
            val frame = waitFrame(media, clip, TrackKind.IMAGE, 0)
            assertNotNull("the preview must receive the decoded image", frame)
            val red = frame!!.bitmap.getPixel(frame.bitmap.width / 2, frame.bitmap.height / 2)
            assertEquals("pixels come from the real file", 0xFF, (red shr 16) and 0xFF)
            assertEquals(0, red and 0xFF)
        } finally { media.close() }
    }

    @Test fun missingMediaProducesNoFakeFrame() {
        val a = import(TestMedia.png(dir, "gone.png", 64, 64), ResourceKind.IMAGE)
        val ins = session.insertAsset(a.id, 0)
        // delete the file the project really references (the importer may have made its own copy)
        File(Uri.parse(a.uri).path!!).delete()
        val clip = session.timeline.requireClip(ins.clipId).second
        val media = PreviewMedia(session, BitmapProvider(context))
        try {
            Thread.sleep(300)
            assertNull("no placeholder pixels for unreadable media", media.frameFor(clip, TrackKind.IMAGE, 0))
        } finally { media.close() }
    }

    @Test fun importedAudioGoesToAnAudioTrackWithItsDuration() {
        val a = import(TestMedia.wav(dir, "tone.wav", 3), ResourceKind.AUDIO)
        val ins = session.insertAsset(a.id, 1_000_000L)
        val (track, clip) = session.timeline.requireClip(ins.clipId)
        assertEquals(TrackKind.AUDIO, track.kind)
        assertEquals(a.id, clip.assetId)
        assertEquals(a.durationUs, clip.durationUs)
        assertTrue("about 3 s", clip.durationUs in 2_900_000L..3_100_000L)
        assertEquals(setOf(clip.id), session.selection.clipIds)
    }

    @Test fun importedVideoKeepsItsDurationAndTheFrameFollowsThePlayhead() {
        val file = TestMedia.mp4(dir, "clip.mp4")
        assumeTrue("no H.264 encoder on this device", file != null)
        val a = import(file!!, ResourceKind.VIDEO)
        assertTrue("about 2 s", a.durationUs in 1_700_000L..2_300_000L)
        val ins = session.insertAsset(a.id, 0)
        val (track, clip) = session.timeline.requireClip(ins.clipId)
        assertEquals(TrackKind.VIDEO, track.kind)
        assertEquals(a.id, clip.assetId)
        assertEquals(a.durationUs, clip.sourceDurationUs)

        val media = PreviewMedia(session, BitmapProvider(context))
        try {
            val first = waitFrame(media, clip, TrackKind.VIDEO, 0)
            assertNotNull("video frame at the start must reach the preview", first)
            val firstLuma = TestMedia.meanLuma(first!!.bitmap)
            // Later playhead position: the displayed frame must change (exact frame, not the nearest key frame).
            val later = waitFrame(media, clip, TrackKind.VIDEO, 1_750_000L)
            assertNotNull(later)
            // the stale first frame may be returned until the new one is decoded: wait for a different key
            val end = System.currentTimeMillis() + 8_000
            var f = later
            while (f!!.key == first.key && System.currentTimeMillis() < end) { Thread.sleep(50); f = media.frameFor(clip, TrackKind.VIDEO, 1_750_000L) }
            assertTrue("a different frame must be shown", f.key != first.key)
            val laterLuma = TestMedia.meanLuma(f.bitmap)
            assertTrue("later frame is brighter ($firstLuma -> $laterLuma)", laterLuma > firstLuma + 60)
        } finally { media.close() }
    }

    @Test fun deviceMediaThatIsAlreadyInTheProjectIsNotListedAsNew() {
        val a = import(TestMedia.png(dir, "dup.png", 32, 32), ResourceKind.IMAGE)
        val lib = MediaLibraryController(context, session, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        try {
            val item = id.motionlab.engine.media.DeviceMedia(a.uri, id.motionlab.core.resource.MediaType.IMAGES, "dup.png", 0, 32, 32, a.sizeBytes, 0, "image/png")
            assertEquals(a.id, lib.assetFor(item)?.id)
        } finally { lib.dispose() }
    }
}
