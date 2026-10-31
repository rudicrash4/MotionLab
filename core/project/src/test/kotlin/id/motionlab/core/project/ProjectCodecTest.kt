package id.motionlab.core.project

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectCodecTest {
    private fun sample() = ProjectDocument(
        id = "p1",
        name = "Demo",
        createdAtMs = 1_000,
        modifiedAtMs = 2_000,
        config = ProjectConfig(1280, 720, 30000, 1001, 5_000_000),
        assets = listOf(AssetRefDto("a1", "content://media/1", "VIDEO", "clip.mp4")),
        tracks = listOf(
            TrackDto(
                id = "t1", name = "Video 1", kind = "VIDEO",
                clips = listOf(
                    ClipDto(
                        id = "c1", assetId = "a1", startUs = 0, durationUs = 2_000_000, sourceStartUs = 500_000,
                        properties = mapOf(
                            "opacity" to listOf(
                                KeyframeDto(0, 0.0),
                                KeyframeDto(1_000_000, 1.0, "bezier", 0.42, 0.0, 0.58, 1.0),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun roundTripPreservesEverything() {
        val codec = ProjectCodec()
        val decoded = codec.decode(codec.encode(sample()))
        assertEquals(sample(), decoded)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val codec = ProjectCodec()
        val text = codec.encode(sample()).replaceFirst("{", "{\"futureField\": 123,")
        assertEquals(sample(), codec.decode(text))
    }

    @Test
    fun garbageIsCorruptData() {
        try {
            ProjectCodec().decode("{ not json")
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.CORRUPT_DATA, e.code)
        }
    }

    @Test
    fun missingRequiredFieldIsCorruptData() {
        try {
            ProjectCodec().decode("{\"schemaVersion\":1,\"name\":\"x\"}")
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.CORRUPT_DATA, e.code)
        }
    }

    @Test
    fun newerSchemaIsRejectedNotTruncated() {
        try {
            ProjectCodec().decode("{\"schemaVersion\":99,\"id\":\"p\",\"name\":\"n\",\"createdAtMs\":1,\"modifiedAtMs\":1}")
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.UNSUPPORTED, e.code)
        }
    }

    @Test
    fun invalidConfigCannotBeEncoded() {
        try {
            ProjectCodec().encode(sample().copy(config = ProjectConfig(width = 0)))
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun migrationsRunInOrder() {
        // Pretend schema 1 is the current one and version-0 files used "title" instead of "name".
        val rename = ProjectMigration(0) { obj ->
            JsonObject(obj.filterKeys { it != "title" } + ("name" to (obj["title"] ?: JsonPrimitive("untitled"))))
        }
        val codec = ProjectCodec(migrations = listOf(rename), currentVersion = 1)
        val old = "{\"id\":\"p\",\"title\":\"Old name\",\"createdAtMs\":1,\"modifiedAtMs\":2}"
        val doc = codec.decode(old)
        assertEquals("Old name", doc.name)
        assertEquals(1, doc.schemaVersion)
    }

    @Test
    fun missingMigrationIsReported() {
        val codec = ProjectCodec(migrations = emptyList(), currentVersion = 1)
        try {
            codec.decode("{\"id\":\"p\",\"name\":\"n\",\"createdAtMs\":1,\"modifiedAtMs\":2}")
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.UNSUPPORTED, e.code)
        }
    }

    @Test
    fun storeSavesLoadsAndKeepsBackup() {
        val dir = java.nio.file.Files.createTempDirectory("mlproj").toFile()
        try {
            val store = ProjectStore(dir)
            assertTrue(!store.exists())
            store.save(sample())
            assertEquals(sample(), store.load())
            val v2 = sample().copy(name = "Renamed")
            store.save(v2)
            assertEquals(v2, store.load())
            assertTrue(File(dir, "project.json.bak").exists())
            assertTrue(!File(dir, "project.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptMainFileRecoversFromBackup() {
        val dir = java.nio.file.Files.createTempDirectory("mlproj").toFile()
        try {
            val store = ProjectStore(dir)
            store.save(sample())
            store.save(sample().copy(name = "second"))
            File(dir, "project.json").writeText("{ truncated by a crash")
            val recovered = store.load()
            assertEquals("Demo", recovered.name) // backup holds the previous complete save
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadWithoutAnyFileIsNotFound() {
        val dir = java.nio.file.Files.createTempDirectory("mlproj").toFile()
        try {
            ProjectStore(dir).load()
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.NOT_FOUND, e.code)
        } finally {
            dir.deleteRecursively()
        }
    }
}
