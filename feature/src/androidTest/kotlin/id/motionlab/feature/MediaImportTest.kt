package id.motionlab.feature

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.resource.ResourceKind
import id.motionlab.engine.media.AssetImporter
import id.motionlab.engine.media.ImportOutcome
import id.motionlab.engine.media.MediaAccess
import id.motionlab.engine.media.WaveformProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Real Android media import: files are created on the device and imported through ContentResolver-based code. Video import
 * is NOT covered here (a test video would have to be encoded on the device); see docs/PHASE3.md.
 */
@RunWith(AndroidJUnit4::class)
class MediaImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var importer: AssetImporter

    @Before fun setUp() {
        dir = File(context.cacheDir, "import-test").also { it.deleteRecursively(); it.mkdirs() }
        importer = AssetImporter(context)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun png(name: String, w: Int, h: Int): File {
        val f = File(dir, name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF336699.toInt()) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    private fun wav(name: String, seconds: Int, rate: Int = 8000): File {
        val n = seconds * rate
        val pcm = ByteArrayOutputStream()
        for (i in 0 until n) {
            val v = (sin(2 * PI * 440 * i / rate) * 20000).toInt()
            pcm.write(v and 0xFF); pcm.write((v shr 8) and 0xFF)
        }
        val data = pcm.toByteArray()
        val out = ByteArrayOutputStream()
        fun le32(v: Int) { for (s in 0..24 step 8) out.write((v shr s) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + data.size); out.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data.size); out.write(data)
        return File(dir, name).also { it.writeBytes(out.toByteArray()) }
    }

    @Test fun imageImportReadsSizeAndKeepsTheOriginal() {
        val src = png("pic.png", 40, 30)
        val r = importer.import(Uri.fromFile(src), setOf(ResourceKind.IMAGE, ResourceKind.VIDEO))
        assertTrue(r.toString(), r is ImportOutcome.Imported)
        val a = (r as ImportOutcome.Imported).asset
        assertEquals("IMAGE", a.kind); assertEquals(40, a.width); assertEquals(30, a.height)
        assertTrue("original must be untouched", src.exists())
        assertTrue("the project must reference a copy it can always open", MediaAccess.isAccessible(context, a))
    }

    @Test fun notAnImageIsRejectedWithAReasonAndLeavesNoCopy() {
        val bad = File(dir, "fake.png").also { it.writeText("this is not a png") }
        val before = File(context.filesDir, "media").listFiles()?.size ?: 0
        val r = importer.import(Uri.fromFile(bad), null)
        assertTrue(r is ImportOutcome.Failed)
        assertEquals(before, File(context.filesDir, "media").listFiles()?.size ?: 0)
    }

    @Test fun wrongCategoryIsRejected() {
        val r = importer.import(Uri.fromFile(wav("a.wav", 1)), setOf(ResourceKind.IMAGE))
        assertTrue(r is ImportOutcome.Failed)
    }

    @Test fun audioImportReadsDurationRateAndChannels() {
        val r = importer.import(Uri.fromFile(wav("tone.wav", 2)), setOf(ResourceKind.AUDIO))
        assertTrue(r.toString(), r is ImportOutcome.Imported)
        val a = (r as ImportOutcome.Imported).asset
        assertEquals("AUDIO", a.kind)
        assertTrue("duration ~2 s but was ${a.durationUs}", a.durationUs in 1_800_000..2_300_000)
        assertEquals(8000, a.sampleRate); assertEquals(1, a.channels)
    }

    @Test fun waveformIsBuiltInTheBackgroundCachedAndCancellable() {
        val a = (importer.import(Uri.fromFile(wav("w.wav", 2)), setOf(ResourceKind.AUDIO)) as ImportOutcome.Imported).asset
        val provider = WaveformProvider(context)
        provider.drop(a.id)
        assertEquals("cancelled work must produce nothing", null, provider.load(a) { true })
        val w = provider.load(a)
        assertNotNull(w)
        assertTrue("a 440 Hz tone at 20000/32768 must show a clear peak", w!!.peakBetween(0, a.durationUs) > 0.4f)
        assertNotNull("second call is served from the cache", provider.cached(a.id))
        assertTrue(File(context.cacheDir, "waveforms/${a.id}.wf").isFile)
        provider.drop(a.id)
    }

    @Test fun invalidFontIsRejectedAndNothingIsStored() {
        val bad = File(dir, "bad.ttf").also { it.writeBytes(ByteArray(200) { 3 }) }
        val before = File(context.filesDir, "fonts").listFiles()?.size ?: 0
        val r = importer.import(Uri.fromFile(bad), setOf(ResourceKind.FONT))
        assertTrue(r is ImportOutcome.Failed)
        assertEquals(before, File(context.filesDir, "fonts").listFiles()?.size ?: 0)
    }

    @Test fun aRealSystemFontIsValidatedStoredAndRegistered() {
        val sys = File("/system/fonts").listFiles { f -> f.name.endsWith(".ttf") && f.length() < 8_000_000 }?.firstOrNull()
        assumeTrue("no TTF in /system/fonts on this device", sys != null)
        val copy = File(dir, "sys.ttf").also { sys!!.copyTo(it, overwrite = true) }
        val r = importer.import(Uri.fromFile(copy), setOf(ResourceKind.FONT))
        assertTrue(r.toString(), r is ImportOutcome.Imported)
        val a = (r as ImportOutcome.Imported).asset
        assertTrue(a.fontFamily!!.isNotBlank())
        val fm = id.motionlab.engine.text.FontManager()
        val ok = fm.registerFontFile(a.fontFamily!!, File(Uri.parse(a.uri).path!!))
        assertTrue(ok is id.motionlab.core.common.MlResult.Success)
    }

    @Test fun missingFilesAreDetected() {
        val src = png("gone.png", 10, 10)
        val a = AssetRefDto("x", Uri.fromFile(src).toString(), "IMAGE", "gone.png")
        assertTrue(MediaAccess.isAccessible(context, a))
        src.delete()
        assertFalse(MediaAccess.isAccessible(context, a))
        assertFalse(MediaAccess.isAccessible(context, AssetRefDto("y", "content://nope.invalid/1", "IMAGE")))
    }
}
