package id.motionlab.engine.timeline

import id.motionlab.core.common.MlException

/**
 * Undo/redo for [Command]s. Every executed command is stored together with the state before and after it. States are
 * immutable and share structure, so this is cheap, and undo/redo restore EXACTLY what was there: a command that
 * would have to be "reversed" by hand can never leave the project in a state nobody has seen.
 *
 * Consecutive commands with the same non-null [Command.mergeKey] (one drag gesture = many small commands) are folded
 * into one undo step: the "before" of the first and the "after" of the last are kept.
 */
class History(initial: EditorState, private val limit: Int = 200) {
    private class Entry(val label: String, val mergeKey: String?, val before: EditorState, var after: EditorState)

    private val undoStack = ArrayList<Entry>()
    private val redoStack = ArrayList<Entry>()

    var state: EditorState = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label
    val undoDepth: Int get() = undoStack.size

    /**
     * Applies [command]. If it throws, the history and the state are unchanged. Returns the new state.
     * Set [allowMerge] false to force a separate undo step (e.g. at the end of a gesture).
     */
    fun execute(command: Command, allowMerge: Boolean = true): EditorState {
        val before = state
        val after = command.apply(before) // may throw MlException / IllegalArgumentException: nothing is recorded then
        val last = undoStack.lastOrNull()
        val key = command.mergeKey
        if (allowMerge && key != null && last != null && last.mergeKey == key && redoStack.isEmpty()) {
            last.after = after
        } else {
            undoStack += Entry(command.label, key, before, after)
            if (undoStack.size > limit) undoStack.removeAt(0)
        }
        redoStack.clear()
        state = after
        return after
    }

    /** Closes the current merge group so the next command with the same key starts a new undo step. */
    fun breakMerge() {
        val last = undoStack.lastOrNull() ?: return
        undoStack[undoStack.size - 1] = Entry(last.label, null, last.before, last.after)
    }

    /** Selection is not an edit, but it must not be lost by undo; it is taken from the restored state. */
    fun undo(): EditorState {
        val e = undoStack.removeLastOrNull() ?: throw MlException(id.motionlab.core.common.ErrorCode.INVALID_ARGUMENT, "Nothing to undo")
        redoStack += e
        state = e.before
        return state
    }

    fun redo(): EditorState {
        val e = redoStack.removeLastOrNull() ?: throw MlException(id.motionlab.core.common.ErrorCode.INVALID_ARGUMENT, "Nothing to redo")
        undoStack += e
        state = e.after
        return state
    }

    /** Replaces the state without recording history (selection changes, project load). */
    fun replaceState(newState: EditorState, clearHistory: Boolean = false) {
        state = newState
        if (clearHistory) {
            undoStack.clear()
            redoStack.clear()
        }
    }
}
