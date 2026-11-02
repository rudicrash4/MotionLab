package id.motionlab.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.EditorState
import id.motionlab.engine.timeline.TimelineViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Bridges the pure [EditorSession] (single source of truth) to Compose: every session change becomes Compose state,
 * and every change schedules an autosave. UI-only state (zoom, scroll, snapping toggle, open panel) lives here;
 * nothing in it is part of the project.
 *
 * [save] is called off the main thread by the caller's scope contract: it must be safe to run on any thread.
 */
class EditorController(
    val session: EditorSession,
    private val baseDocument: ProjectDocument,
    private val scope: CoroutineScope,
    private val save: suspend (ProjectDocument) -> Unit = {},
    private val autosaveDelayMs: Long = 1_500,
) {
    var state: EditorState by mutableStateOf(session.state)
        private set

    /** Playhead as Compose state; the session's clock is the real source (see [pollPlayhead]). */
    var playheadUs: Long by mutableLongStateOf(session.currentTimeUs)
        private set

    var playing: Boolean by mutableStateOf(false)
        private set

    var viewport: TimelineViewport by mutableStateOf(TimelineViewport())
    var snapEnabled: Boolean by mutableStateOf(true)
    var multiSelect: Boolean by mutableStateOf(false)

    /** Width of the clip area in px; set by the ruler so playback can keep the playhead in view. */
    var timelineWidthPx: Float = 0f
    var message: String? by mutableStateOf(null)
    var saveStatus: String by mutableStateOf("Saved")
        private set

    /** Bumped when the playhead is moved by the user or by playback, so panels can re-read values at the playhead. */
    private var autosaveJob: Job? = null
    private var dirty = false

    private var lastTimeline = session.timeline

    private val listener: (EditorState) -> Unit = {
        state = it
        playheadUs = session.currentTimeUs
        // Selection changes are not project changes: only a different timeline needs saving.
        if (it.timeline !== lastTimeline) {
            lastTimeline = it.timeline
            dirty = true
            saveStatus = "Unsaved changes"
            scheduleAutosave()
        }
    }

    init {
        session.addListener(listener)
    }

    fun dispose() {
        session.removeListener(listener)
        session.pause()
        autosaveJob?.cancel()
    }

    /** Runs an edit; a rejected edit (locked track, overlap...) is shown as a message and changes nothing. */
    fun run(block: () -> Unit): Boolean = try {
        block()
        message = null
        true
    } catch (e: MlException) {
        message = e.message
        false
    }

    fun seek(timeUs: Long) {
        session.seekTo(timeUs)
        playheadUs = session.currentTimeUs
    }

    fun togglePlay() {
        if (session.isPlaying) session.pause() else session.play()
        playing = session.isPlaying
        playheadUs = session.currentTimeUs
    }

    /** Call once per frame while the editor is visible; cheap when nothing moves. */
    fun pollPlayhead() {
        val p = session.isPlaying
        if (p != playing) playing = p
        if (p) {
            val t = session.currentTimeUs
            if (t != playheadUs) playheadUs = t
        }
    }

    fun ownerOfSelection(): AnimationOwner? {
        val sel = state.selection
        sel.clipIds.firstOrNull()?.let { return AnimationOwner.ClipOwner(it) }
        sel.layerIds.firstOrNull()?.let { return AnimationOwner.LayerOwner(it) }
        sel.keyframes.firstOrNull()?.let { return it.owner }
        return null
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = scope.launch {
            delay(autosaveDelayMs)
            flush()
        }
    }

    /** Writes the project now (also used when leaving the editor). */
    suspend fun flush() {
        if (!dirty) return
        val doc = session.toDocument(baseDocument)
        try {
            save(doc)
            dirty = false
            saveStatus = "Saved"
        } catch (e: Exception) {
            MlLog.e("EditorController", "autosave failed", e)
            saveStatus = "Save FAILED: ${e.message}"
        }
    }
}
