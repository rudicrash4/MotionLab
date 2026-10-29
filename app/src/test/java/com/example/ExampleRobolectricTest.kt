package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import id.motionlab.editor.core.Clip
import id.motionlab.editor.core.Project
import id.motionlab.editor.core.ProjectSerializer
import id.motionlab.editor.core.Track
import id.motionlab.editor.core.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MotionLab", appName)
    }

    @Test
    fun `verify reverse playback calculation`() {
        val normalClip = Clip(
            type = TrackType.VIDEO,
            name = "Test Video",
            startMs = 1000L,
            durationMs = 5000L,
            isReversed = false
        )
        assertEquals(2000.0, normalClip.effectiveLocal(2000.0), 0.001)

        val reversedClip = normalClip.copy(isReversed = true)
        assertEquals(3000.0, reversedClip.effectiveLocal(2000.0), 0.001)
    }

    @Test
    fun `verify project serialization with muted and reversed properties`() {
        val clip = Clip(
            type = TrackType.VIDEO,
            name = "Clip 1",
            startMs = 0L,
            durationMs = 4000L,
            volume = 1.5f,
            isMuted = true,
            isReversed = true
        )
        val project = Project(
            name = "Test Project",
            tracks = listOf(Track(id = "tr1", type = TrackType.VIDEO, name = "Layer 1", clips = listOf(clip))),
            bgColor = 0xFF181A22.toInt()
        )

        val json = ProjectSerializer.toJson(project)
        val loaded = ProjectSerializer.fromJson(json)

        val loadedClip = loaded.tracks.first().clips.first()
        assertTrue(loadedClip.isMuted)
        assertTrue(loadedClip.isReversed)
        assertEquals(1.5f, loadedClip.volume, 0.001f)
        assertEquals(0xFF181A22.toInt(), loaded.bgColor)
    }
}
