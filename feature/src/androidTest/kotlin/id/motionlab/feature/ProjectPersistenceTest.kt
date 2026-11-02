package id.motionlab.feature

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.motionlab.core.project.CURRENT_SCHEMA_VERSION
import id.motionlab.core.project.ProjectDocument
import id.motionlab.core.project.ProjectStore
import id.motionlab.engine.animation.Easing
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.MotionPath
import id.motionlab.engine.animation.PresetLibrary
import id.motionlab.engine.animation.PropertyNames
import id.motionlab.engine.timeline.AddLayer
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.IdSource
import id.motionlab.engine.timeline.Layer
import id.motionlab.engine.timeline.LayerType
import id.motionlab.engine.timeline.LinkTrackToLayer
import id.motionlab.engine.timeline.SetKeyframe
import id.motionlab.engine.timeline.SetMotionPath
import id.motionlab.engine.timeline.SetTrackFlags
import id.motionlab.engine.timeline.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Save / load / autosave / migration through the real ProjectStore on the device file system. */
@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {
    private fun tempDir(): File {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(base, "ml-project-test-" + System.nanoTime()).also { it.mkdirs(); it.deleteOnExit() }
    }

    private fun richSession(): EditorSession {
        val s = EditorSession(ids = IdSource.sequential())
        val shape = s.addTrack(TrackKind.SHAPE)
        val text = s.addTrack(TrackKind.TEXT)
        val clip = s.addClip(shape, 500_000, 3_000_000, payload = mapOf("shape" to "ellipse", "color" to "#FF7043"))
        s.addClip(text, 0, 2_000_000, payload = mapOf("text" to "Hello"))
        val layer = Layer("L1", "Parent", LayerType.CONTENT)
        s.execute(AddLayer(layer))
        s.execute(LinkTrackToLayer(shape, "L1"))
        val owner = AnimationOwner.ClipOwner(clip)
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, Keyframe(0, 0.0, Easing.EASE_IN_OUT)))
        s.execute(SetKeyframe(owner, PropertyNames.OPACITY, Keyframe(1_000_000, 1.0, Interpolation.Hold)))
        s.execute(SetKeyframe(owner, PropertyNames.ROTATION, Keyframe(0, 0.0, Interpolation.Bezier(0.1, 0.9, 0.2, 1.0))))
        s.execute(SetKeyframe(owner, PropertyNames.ROTATION, Keyframe(2_000_000, 90.0)))
        s.execute(SetMotionPath(owner, MotionPath.smoothThrough(listOf(-300.0 to 0.0, 0.0 to -200.0, 300.0 to 0.0)).withRotateAlongPath(true)))
        s.execute(SetKeyframe(owner, PropertyNames.MOTION_PROGRESS, Keyframe(0, 0.0)))
        s.execute(SetKeyframe(owner, PropertyNames.MOTION_PROGRESS, Keyframe(3_000_000, 1.0)))
        s.execute(SetKeyframe(AnimationOwner.LayerOwner("L1"), PropertyNames.POSITION_X, Keyframe(0, 0.0)))
        s.execute(SetKeyframe(AnimationOwner.LayerOwner("L1"), PropertyNames.POSITION_X, Keyframe(3_000_000, 120.0)))
        s.applyPreset(AnimationOwner.ClipOwner(s.timeline.track(text).clips[0].id), PresetLibrary.all().first().id)
        s.execute(SetTrackFlags(text, muted = true, collapsed = true))
        return s
    }

    @Test
    fun everythingSurvivesSaveAndLoadAndRendersIdentically() {
        val original = richSession()
        val dir = tempDir()
        val base = ProjectDocument(id = "p1", name = "Rich", createdAtMs = 1, modifiedAtMs = 1)
        ProjectStore(dir).save(original.toDocument(base))
        val loaded = EditorSession.fromDocument(ProjectStore(dir).load())

        assertEquals(original.timeline.tracks.map { it.id to it.kind }, loaded.timeline.tracks.map { it.id to it.kind })
        assertEquals(original.timeline.layers.map { it.id }, loaded.timeline.layers.map { it.id })
        // the shared evaluator gives the same frame from the loaded project as from the live one
        for (t in listOf(0L, 400_000L, 1_000_000L, 1_700_000L, 2_900_000L)) {
            val a = original.frameAt(t).items
            val b = loaded.frameAt(t).items
            assertEquals("items at $t", a.map { it.clip.id }, b.map { it.clip.id })
            for (i in a.indices) {
                assertEquals("opacity at $t", a[i].opacity, b[i].opacity, 1e-9)
                assertEquals("world.a at $t", a[i].world.a, b[i].world.a, 1e-9)
                assertEquals("world.e at $t", a[i].world.e, b[i].world.e, 1e-9)
                assertEquals("world.f at $t", a[i].world.f, b[i].world.f, 1e-9)
            }
        }
        // keyframe ids are stable across save/load
        val clipId = original.timeline.tracks.first { it.kind == TrackKind.SHAPE }.clips.single().id
        val idsBefore = original.anim(AnimationOwner.ClipOwner(clipId)).properties.track(PropertyNames.OPACITY).keyframes.map { it.id }
        val idsAfter = loaded.anim(AnimationOwner.ClipOwner(clipId)).properties.track(PropertyNames.OPACITY).keyframes.map { it.id }
        assertEquals(idsBefore, idsAfter)
        assertEquals(original.anim(AnimationOwner.ClipOwner(clipId)).motionPath, loaded.anim(AnimationOwner.ClipOwner(clipId)).motionPath)
    }

    @Test
    fun aPhase1FileStillLoadsAndKeyframesGetIds() {
        val dir = tempDir()
        File(dir, "project.json").writeText(
            """{"schemaVersion":1,"id":"p","name":"Old","createdAtMs":1,"modifiedAtMs":2,
               "config":{"width":1280,"height":720,"frameRateNumerator":30,"frameRateDenominator":1,"durationUs":1000000},
               "tracks":[{"id":"t1","name":"V","kind":"VIDEO","clips":[
                 {"id":"c1","startUs":0,"durationUs":1000000,
                  "properties":{"opacity":[{"timeUs":0,"value":0.0,"interpolation":"linear"},{"timeUs":500000,"value":1.0,"interpolation":"hold"}]}}]}]}""",
        )
        val doc = ProjectStore(dir).load()
        assertEquals(CURRENT_SCHEMA_VERSION, doc.schemaVersion)
        val s = EditorSession.fromDocument(doc)
        val kfs = s.anim(AnimationOwner.ClipOwner("c1")).properties.track(PropertyNames.OPACITY).keyframes
        assertEquals(2, kfs.size)
        assertTrue(kfs.all { it.id.isNotBlank() } && kfs.map { it.id }.toSet().size == 2)
        assertEquals(Interpolation.Hold, kfs[1].interpolation)
        // and it can be saved again in the new format
        ProjectStore(dir).save(s.toDocument(doc))
        assertEquals(CURRENT_SCHEMA_VERSION, ProjectStore(dir).load().schemaVersion)
    }

    @Test
    fun autosaveIsDebouncedAndWritesTheLatestState() {
        val saved = ArrayList<ProjectDocument>()
        val s = EditorSession(ids = IdSource.sequential())
        val c = EditorController(s, testDocument(), CoroutineScope(SupervisorJob() + Dispatchers.Default), { synchronized(saved) { saved += it } }, autosaveDelayMs = 300)
        repeat(5) { s.addTrack(TrackKind.SHAPE); Thread.sleep(30) }
        assertEquals("nothing is written while edits keep coming", 0, synchronized(saved) { saved.size })
        waitUntil { synchronized(saved) { saved.isNotEmpty() } }
        Thread.sleep(600)
        synchronized(saved) {
            assertEquals("one write for the whole burst", 1, saved.size)
            assertEquals(5, saved[0].tracks.size)
        }
        assertEquals("Saved", c.saveStatus)
        c.dispose()
    }

    @Test
    fun selectionChangesDoNotTriggerASave() {
        val saved = ArrayList<ProjectDocument>()
        val s = EditorSession(ids = IdSource.sequential())
        val t = s.addTrack(TrackKind.SHAPE)
        val clip = s.addClip(t, 0, 1_000_000)
        val c = EditorController(s, testDocument(), CoroutineScope(SupervisorJob() + Dispatchers.Default), { synchronized(saved) { saved += it } }, autosaveDelayMs = 100)
        runBlocking { c.flush() }
        val n = synchronized(saved) { saved.size }
        s.select(id.motionlab.engine.timeline.Selection(clipIds = setOf(clip)))
        Thread.sleep(500)
        assertEquals(n, synchronized(saved) { saved.size })
        c.dispose()
    }

    @Test
    fun flushWritesPendingChangesAndAFailedSaveIsReportedAndRetried() {
        var fail = true
        val saved = ArrayList<ProjectDocument>()
        val s = EditorSession(ids = IdSource.sequential())
        val c = EditorController(s, testDocument(), CoroutineScope(SupervisorJob() + Dispatchers.Default), { if (fail) throw RuntimeException("disk full") else saved += it }, autosaveDelayMs = 60_000)
        s.addTrack(TrackKind.TEXT)
        runBlocking { c.flush() }
        assertTrue(c.saveStatus, c.saveStatus.startsWith("Save FAILED"))
        fail = false
        runBlocking { c.flush() }
        assertEquals("Saved", c.saveStatus)
        assertEquals(1, saved.size)
        c.dispose()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met within $timeoutMs ms")
            Thread.sleep(20)
        }
    }
}
