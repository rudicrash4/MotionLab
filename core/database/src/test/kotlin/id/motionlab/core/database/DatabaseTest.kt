package id.motionlab.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseTest {
    private lateinit var db: MotionLabDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotionLabDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun project(id: String, modified: Long) = ProjectEntity(
        id = id, name = "Project $id", directoryPath = "/projects/$id",
        createdAtMs = 1, modifiedAtMs = modified, width = 1920, height = 1080,
        frameRateNumerator = 30, frameRateDenominator = 1, durationUs = 0,
    )

    private fun asset(id: String, projectId: String, imported: Long = 1) = AssetEntity(
        id = id, projectId = projectId, uri = "content://media/$id", kind = "VIDEO",
        displayName = "$id.mp4", mimeType = "video/mp4", durationUs = 1_000_000,
        width = 1280, height = 720, importedAtMs = imported,
    )

    @Test
    fun projectsRoundTripAndOrderByLastModified() = runBlocking {
        db.projectDao().upsert(project("old", 10))
        db.projectDao().upsert(project("new", 20))
        assertEquals(listOf("new", "old"), db.projectDao().getAll().map { it.id })
        assertEquals(listOf("new", "old"), db.projectDao().observeAll().first().map { it.id })
        assertEquals(project("old", 10), db.projectDao().getById("old"))
        assertEquals(2, db.projectDao().count())
    }

    @Test
    fun upsertReplacesExistingRow() = runBlocking {
        db.projectDao().upsert(project("p", 1))
        db.projectDao().upsert(project("p", 99).copy(name = "Renamed"))
        assertEquals(1, db.projectDao().count())
        assertEquals("Renamed", db.projectDao().getById("p")!!.name)
    }

    @Test
    fun deletingProjectCascadesToAssets() = runBlocking {
        db.projectDao().upsert(project("p", 1))
        db.assetDao().upsert(asset("a1", "p", 1))
        db.assetDao().upsert(asset("a2", "p", 2))
        assertEquals(listOf("a1", "a2"), db.assetDao().getByProject("p").map { it.id })
        assertEquals(1, db.projectDao().deleteById("p"))
        assertNull(db.assetDao().getById("a1"))
        assertEquals(0, db.assetDao().getByProject("p").size)
    }

    @Test
    fun assetWithoutProjectIsRejectedByForeignKey() {
        val failed = try {
            runBlocking { db.assetDao().upsert(asset("orphan", "missing-project")) }
            false
        } catch (e: Exception) {
            true
        }
        assertEquals(true, failed)
    }

    @Test
    fun assetDeleteRemovesOnlyThatAsset() = runBlocking {
        db.projectDao().upsert(project("p", 1))
        db.assetDao().upsert(asset("a1", "p"))
        db.assetDao().upsert(asset("a2", "p", 2))
        assertEquals(1, db.assetDao().deleteById("a1"))
        assertNotNull(db.assetDao().getById("a2"))
    }

    @Test
    fun schemaHasNoMigrationsYet() {
        assertEquals(0, MotionLabMigrations.ALL.size)
    }
}
