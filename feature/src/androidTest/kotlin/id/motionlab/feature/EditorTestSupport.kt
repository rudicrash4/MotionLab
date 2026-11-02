package id.motionlab.feature

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.IdSource
import id.motionlab.engine.timeline.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun testDocument() = ProjectDocument(id = "test-project", name = "Test", createdAtMs = 1L, modifiedAtMs = 1L)

/** A session with one SHAPE track holding one 2 s clip that starts at 1 s (x = 100..300 px at the default 100 px/s zoom). */
class EditorFixture(val rule: ComposeContentTestRule, val saved: MutableList<ProjectDocument> = ArrayList(), autosaveMs: Long = 60_000) {
    val session = EditorSession(ids = IdSource.sequential())
    val trackId: String = session.addTrack(TrackKind.SHAPE)
    val clipId: String = session.addClip(trackId, 1_000_000, 2_000_000, payload = mapOf("shape" to "rect", "color" to "#4F8CFF"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = EditorController(session, testDocument(), scope, { synchronized(saved) { saved += it } }, autosaveMs)

    init {
        controller.snapEnabled = false // exact pixel -> time maths in the tests
    }

    fun show(): EditorFixture {
        rule.setContent { MaterialTheme { EditorLayout(controller, "Test") {} } }
        rule.waitForIdle()
        return this
    }
}
