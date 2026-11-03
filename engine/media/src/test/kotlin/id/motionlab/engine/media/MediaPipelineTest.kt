package id.motionlab.engine.media

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlResult
import id.motionlab.core.resource.ResourceKind
import id.motionlab.core.resource.ResourceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPipelineTest {
    private class FakeBackend(
        override val name: String,
        private val available: Boolean = true,
        private val result: () -> MediaMetadata,
    ) : MetadataBackend {
        var calls = 0
        override fun isAvailable() = available
        override fun extract(uri: String): MediaMetadata {
            calls++
            return result()
        }
    }

    private fun video() = MediaMetadata("video/mp4", 2_000_000, VideoStreamInfo("h264", 1280, 720, 30.0, 0), null, 0, "fake")
    private fun audio() = MediaMetadata("audio/mpeg", 3_000_000, null, AudioStreamInfo("mp3", 44100, 2, 0), 0, "fake")
    private fun image() = MediaMetadata("image/png", 0, VideoStreamInfo("", 100, 100, 0.0, 0), null, 0, "fake")

    @Test
    fun resolverFallsBackToNextBackend() {
        val bad = FakeBackend("a") { throw MlException(ErrorCode.DECODE, "nope") }
        val good = FakeBackend("b") { video() }
        val skipped = FakeBackend("c", available = false) { throw AssertionError("must not be called") }
        val m = MetadataResolver(listOf(bad, skipped, good)).resolve("file:///x.mp4")
        assertEquals(1280, m.video!!.width)
        assertEquals(1, bad.calls)
        assertEquals(0, skipped.calls)
    }

    @Test
    fun resolverReportsAllFailures() {
        val r = MetadataResolver(listOf(FakeBackend("a") { throw MlException(ErrorCode.DECODE, "x") }))
        try {
            r.resolve("u")
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.DECODE, e.code)
            assertTrue(e.message!!.contains("a: x"))
        }
        try {
            MetadataResolver(emptyList()).resolve("u")
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.UNSUPPORTED, e.code)
        }
    }

    @Test
    fun importerClassifiesAndRegisters() {
        val resources = ResourceManager()
        var n = 0
        fun importer(m: MediaMetadata) =
            MediaImporter(MetadataResolver(listOf(FakeBackend("f") { m })), resources) { "id${n++}" }

        val v = (importer(video()).import("content://v", "v.mp4") as MlResult.Success).value
        assertEquals(ResourceKind.VIDEO, v.ref.kind)
        assertEquals(ResourceKind.AUDIO, (importer(audio()).import("content://a") as MlResult.Success).value.ref.kind)
        assertEquals(ResourceKind.IMAGE, (importer(image()).import("content://i") as MlResult.Success).value.ref.kind)
        assertEquals(3, resources.all().size)
    }

    @Test
    fun importerRejectsFilesWithoutStreams() {
        val empty = MediaMetadata(null, 0, null, null, 0, "fake")
        val r = MediaImporter(MetadataResolver(listOf(FakeBackend("f") { empty })), ResourceManager()).import("content://x")
        assertEquals(ErrorCode.UNSUPPORTED, (r as MlResult.Failure).error.code)
    }

    @Test
    fun capabilitiesAreCaseInsensitive() {
        val c = MediaCapabilities.of(setOf("VIDEO/AVC"), setOf("video/avc"))
        assertTrue(c.canDecode("video/avc"))
        assertTrue(!c.canEncode("video/hevc"))
        assertEquals(listOf("video/avc"), c.supportedExportVideoMimes())
    }
}
