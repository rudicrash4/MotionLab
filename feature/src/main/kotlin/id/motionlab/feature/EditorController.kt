package id.motionlab.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.timeline.AnimationOwner
import id.motionlab.engine.timeline.Command
import id.motionlab.engine.timeline.EditorSession
import id.motionlab.engine.timeline.EditorState
import id.motionlab.engine.timeline.Layer
import id.motionlab.engine.timeline.Selection
import id.motionlab.engine.timeline.Timeline
import id.motionlab.engine.timeline.TimelineViewport
import id.motionlab.engine.timeline.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What a track header needs. A data class so Compose can skip headers whose track did not change structurally. */
data class TrackHeaderModel(
    val id: String,
    val name: String,
    val kind: TrackKind,
    val locked: Boolean,
    val visible: Boolean,
    val muted: Boolean,
    val collapsed: Boolean,
    val layerName: String?,
)

enum class EditorTool(val label: String) { EDIT("Edit"), MOVE("Move"), TRANSFORM("Transform") }

enum class PreviewBackground(val label: String) { CHECKER("Checker"), BLACK("Black"), GRAY("Gray"), WHITE("White") }

/** Preview options read by the render thread (volatile) and edited by the UI. Not part of the project. */
class PreviewSettings {
    @Volatile var background: PreviewBackground = PreviewBackground.CHECKER
    @Volatile var showGrid: Boolean = false
}

/**
 * Bridges the pure [EditorSession] (single source of truth) to Compose with SEPARATE pieces of state, so a change only
 * invalidates what really depends on it:
 *  - [timeline] / [selection] / [playheadUs] / [viewport]: read inside draw lambdas and tiny composables only;
 *  - [headers] / [layers]: structural snapshots compared by value, so moving a clip does not touch them;
 *  - [canUndo] / [canRedo]: booleans.
 * Gestures use [beginGesture]: a transient preview (see EditorSession.preview), committed once at the end.
 */
class EditorController(
    val session: EditorSession,
    private val baseDocument: ProjectDocument,
    private val scope: CoroutineScope,
    private val save: suspend (ProjectDocument) -> Unit = {},
    private val autosaveDelayMs: Long = 1_500,
) {
    var timeline: Timeline by mutableStateOf(session.timeline)
        private set
    var selection: Selection by mutableStateOf(session.selection)
        private set
    var headers: List<TrackHeaderModel> by mutableStateOf(headersOf(session.timeline))
        private set
    var layers: List<Layer> by mutableStateOf(session.timeline.layers)
        private set
    var canUndo: Boolean by mutableStateOf(session.canUndo)
        private set
    var canRedo: Boolean by mutableStateOf(session.canRedo)
        private set
    var playheadUs: Long by mutableLongStateOf(session.currentTimeUs)
        private set
    var playing: Boolean by mutableStateOf(false)
        private set

    var viewport: TimelineViewport by mutableStateOf(TimelineViewport())
    var snapEnabled: Boolean by mutableStateOf(true)
    var multiSelect: Boolean by mutableStateOf(false)
    var tool: EditorTool by mutableStateOf(EditorTool.EDIT)
    var message: String? by mutableStateOf(null)
    var saveStatus: String by mutableStateOf("Saved")
        private set

    /** Width of the clip area in px; set by the ruler so playback can keep the playhead in view. */
    var timelineWidthPx: Float = 0f

    val previewSettings = PreviewSettings()
    var settingsVersion: Int by mutableIntStateOf(0)

    /** Real content sizes measured by the renderer (text layout), keyed by clip id. Written on the render thread. */
    val contentSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    /** Plain read of the session state for tests and one-off lookups (NOT observable). */
    val state: EditorState get() = session.state

    private var autosaveJob: Job? = null
    private var dirty = false
    private var lastCommitted: Timeline = session.committedState.timeline

    private val listener: (EditorState) -> Unit = { st ->
        if (st.timeline !== timeline) timeline = st.timeline
        selection = st.selection
        val h = headersOf(st.timeline)
        if (h != headers) headers = h
        if (st.timeline.layers != layers) layers = st.timeline.layers
        canUndo = session.canUndo
        canRedo = session.canRedo
        playheadUs = session.currentTimeUs
        // A transient preview is not a project change; only a committed, different timeline needs saving.
        if (!session.inTransaction) {
            val committed = session.committedState.timeline
            if (committed !== lastCommitted) {
                lastCommitted = committed
                dirty = true
                saveStatus = "Unsaved changes"
                scheduleAutosave()
            }
        }
    }

    init {
        session.addListener(listener)
    }

    private fun headersOf(tl: Timeline): List<TrackHeaderModel> = tl.tracks.map { t ->
        TrackHeaderModel(t.id, t.name, t.kind, t.locked, t.visible, t.muted, t.collapsed, t.layerId?.let { tl.findLayer(it)?.name })
    }

    fun dispose() {
        session.removeListener(listener)
        session.cancelPreview()
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

    /** One finger-down..up edit: many cheap [EditGesture.update]s, one undo step at [EditGesture.commit]. */
    fun beginGesture(): EditGesture = EditGesture(this)

    class EditGesture internal constructor(private val c: EditorController) {
        private var last: Command? = null
        private var finished = false

        /** Shows [command] (computed from the committed state) without recording anything. False if it was rejected. */
        fun update(command: Command): Boolean {
            if (finished) return false
            return try {
                c.session.preview(command)
                last = command
                c.message = null
                true
            } catch (e: MlException) {
                c.message = e.message
                false
            }
        }

        /** Records the last accepted preview as one undo step. */
        fun commit() {
            if (finished) return
            finished = true
            val cmd = last
            if (cmd == null) {
                c.session.cancelPreview()
                return
            }
            c.run { c.session.commit(cmd) }
        }

        fun commit(final: Command) {
            if (finished) return
            finished = true
            c.run { c.session.commit(final) }
        }

        /** Gesture aborted (cancelled by the system, a second finger, a parent scroll): nothing was recorded. */
        fun cancel() {
            if (finished) return
            finished = true
            c.session.cancelPreview()
        }
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

    /** Call about once per frame while playing; writes state only when something changed. */
    fun pollPlayhead() {
        val p = session.isPlaying
        if (p != playing) playing = p
        if (p) {
            val t = session.currentTimeUs
            if (t != playheadUs) playheadUs = t
        }
    }

    fun undo() {
        if (session.canUndo) { session.undo(); message = null }
    }

    fun redo() {
        if (session.canRedo) { session.redo(); message = null }
    }

    fun ownerOfSelection(): AnimationOwner? {
        val sel = selection
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

    /** Writes the COMMITTED project now (never a gesture preview). Mapping and file I/O run off the main thread. */
    suspend fun flush() {
        if (!dirty) return
        try {
            val doc = withContext(Dispatchers.Default) { session.toDocument(baseDocument) }
            save(doc)
            dirty = false
            saveStatus = "Saved"
        } catch (e: Exception) {
            MlLog.e("EditorController", "autosave failed", e)
            saveStatus = "Save FAILED: ${e.message}"
        }
    }
}
