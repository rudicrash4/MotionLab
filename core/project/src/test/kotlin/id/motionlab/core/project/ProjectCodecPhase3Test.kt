package id.motionlab.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 3 added optional asset fields and blend/mask payload keys; schema 2 files must keep loading unchanged. */
class ProjectCodecPhase3Test {
    private val codec = ProjectCodec()

    @Test fun schema2FileWithoutPhase3FieldsLoadsWithDefaults() {
        val old = """{"schemaVersion":2,"id":"p","name":"n","createdAtMs":1,"modifiedAtMs":1,
            "assets":[{"id":"x","uri":"file:///a.mp4","kind":"VIDEO"}],
            "tracks":[{"id":"t","name":"V","kind":"VIDEO","clips":[{"id":"c","assetId":"x","startUs":0,"durationUs":1000000}]}]}"""
        val d = codec.decode(old)
        val a = d.assets.single()
        assertEquals(0L, a.durationUs)
        assertEquals(0, a.width)
        assertNull(a.fontFamily)
        assertEquals(emptyMap<String, String>(), d.tracks.single().clips.single().payload)
    }

    @Test fun assetMetadataAndPayloadRoundTripThroughJson() {
        val doc = ProjectDocument(
            id = "p", name = "n", createdAtMs = 1, modifiedAtMs = 1,
            assets = listOf(
                AssetRefDto("a", "content://x/1", "AUDIO", "s.mp3", durationUs = 9_000_000, mimeType = "audio/mpeg", sampleRate = 44100, channels = 2, sizeBytes = 123, addedAtMs = 5),
                AssetRefDto("f", "file:///data/fonts/f.ttf", "FONT", "f.ttf", fontFamily = "Fancy"),
            ),
            tracks = listOf(TrackDto("t", "I", "IMAGE", clips = listOf(ClipDto("c", "a", 0, 1_000_000, payload = mapOf("blend" to "MULTIPLY", "mask" to "ELLIPSE", "mask.w" to "0.4"))))),
        )
        val back = codec.decode(codec.encode(doc))
        assertEquals(doc.assets, back.assets)
        assertEquals(doc.tracks.single().clips.single().payload, back.tracks.single().clips.single().payload)
    }
}
