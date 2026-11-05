package id.motionlab.engine.timeline

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.project.AssetRefDto
import id.motionlab.core.project.ProjectDocument
import id.motionlab.engine.animation.AppliedPreset
import id.motionlab.engine.animation.Interpolation
import id.motionlab.engine.animation.Keyframe
import id.motionlab.engine.animation.KeyframeIds
import id.motionlab.engine.animation.PropertyRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

fun interface IdSource {
    fun next(kind: String): String

    companion object {
        val RANDOM = IdSource { kind -> kind + "-" + UUID.randomUUID().toString().take(8) }

        /** Deterministic ids for tests: clip-1, clip-2, ... */
        fun sequential(): IdSource {
            val counters = HashMap<String, Int>()
            return IdSource { kind -> kind + "-" + counters.merge(kind, 1, Int::plus) }
        }
    }
}

/**
 * The single source of truth of the editor: timeline, selection, undo history and the playback clock.
 * UI, preview and (later) export all read from here:
 *  - [frame] resolves the current time into draw items through [SceneEvaluator] (the shared animation evaluator),
 *  - [currentTimeUs] is the one playhead, driven by a monotonic clock, not by frame callbacks,
 *  - every change goes through [execute] so it is undoable and observable.
 *
 * Thread-safe: edits are serialised; readers get immutable snapshots.
 */
class EditorSession(
    initial: Timeline = Timeline(),
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1080,
    val fps: Double = 30.0,
    private val ids: IdSource = IdSource.RANDOM,
    private val clock: TimelineClock = TimelineClock(),
    initialAssets: List<AssetRefDto> = emptyList(),
) {
    private val history = History(EditorState(initial))
    private val listeners = CopyOnWriteArrayList<(EditorState) -> Unit>()

    @Volatile
    private var current: EditorState = history.state

    // ---- media assets (project-level references; NOT part of undo history: removing/adding media is a library action) ----
    @Volatile
    private var assetList: List<AssetRefDto> = initialAssets

    /** Bumped on every library change so observers (autosave) can tell. */
    @Volatile
    var assetsVersion: Int = 0
        private set

    val assets: List<AssetRefDto> get() = assetList

    fun asset(id: String?): AssetRefDto? = id?.let { i -> assetList.firstOrNull { it.id == i } }

    /** Adds or replaces (same id) library entries. */
    @Synchronized
    fun putAssets(items: List<AssetRefDto>) {
        if (items.isEmpty()) return
        val byId = LinkedHashMap<String, AssetRefDto>()
        assetList.forEach { byId[it.id] = it }
        items.forEach { byId[it.id] = it }
        assetList = byId.values.toList()
        assetsVersion++
        for (l in listeners) l(current)
    }

    /** Clips that use [assetId]. */
    fun clipsUsing(assetId: String): List<String> = timeline.tracks.flatMap { t -> t.clips.filter { it.assetId == assetId }.map { it.id } }

    /** Removes the library REFERENCE only (the user's file is never touched). Refused while clips still use it. */
    @Synchronized
    fun removeAsset(assetId: String) {
        val used = clipsUsing(assetId)
        if (used.isNotEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, "Media is used by ${used.size} clip(s); delete them first")
        if (assetList.none { it.id == assetId }) throw MlException(ErrorCode.NOT_FOUND, "Unknown media $assetId")
        assetList = assetList.filter { it.id != assetId }
        assetsVersion++
        for (l in listeners) l(current)
    }

    /**
     * Puts an imported asset on the timeline at [startUs]: reuses the last unlocked track of the right kind or creates one,
     * in ONE undo step. Returns the new clip id.
     */
    @Synchronized
    fun addAssetClip(assetId: String, startUs: Long): String {
        val a = asset(assetId) ?: throw MlException(ErrorCode.NOT_FOUND, "Unknown media $assetId")
        val plan = AssetPlanner.plan(a, canvasWidth, canvasHeight)
        val existing = timeline.tracks.lastOrNull { it.kind == plan.trackKind && !it.locked }
        val trackId = existing?.id ?: ids.next("track")
        val start = existing?.firstFreeStart(startUs, plan.durationUs) ?: startUs
        val clipId = ids.next("clip")
        val clip = MediaClip(clipId, start, plan.durationUs, assetId, payload = plan.payload, sourceDurationUs = plan.sourceDurationUs)
        val cmds = ArrayList<Command>(2)
        if (existing == null) cmds += AddTrack(Track(trackId, "${plan.trackKind.label} ${timeline.tracks.count { it.kind == plan.trackKind } + 1}", plan.trackKind))
        cmds += AddClip(trackId, clip)
        execute(CompositeCommand("Add ${a.displayName ?: "media"}", cmds))
        select(Selection(clipIds = setOf(clipId)))
        return clipId
    }

    @Synchronized
    fun duplicateTrack(trackId: String): String {
        val newId = ids.next("track")
        val ids2 = timeline.track(trackId).clips.map { ids.next("clip") }
        execute(DuplicateTrack(trackId, newId, ids2))
        return newId
    }

    @Volatile
    private var clipboardClips: ClipboardClips? = null

    @Volatile
    private var clipboardKeyframes: ClipboardKeyframes? = null

    val state: EditorState get() = current
    val timeline: Timeline get() = current.timeline
    val selection: Selection get() = current.selection
    val canUndo: Boolean @Synchronized get() = history.canUndo
    val canRedo: Boolean @Synchronized get() = history.canRedo
    val undoLabel: String? @Synchronized get() = history.undoLabel
    val redoLabel: String? @Synchronized get() = history.redoLabel
    val hasClipClipboard: Boolean get() = clipboardClips?.isEmpty == false
    val hasKeyframeClipboard: Boolean get() = clipboardKeyframes?.isEmpty == false

    fun addListener(l: (EditorState) -> Unit) {
        listeners += l
    }

    fun removeListener(l: (EditorState) -> Unit) {
        listeners -= l
    }

    private fun publish() {
        transientActive = false
        current = history.state
        for (l in listeners) l(current)
    }

    // ---- gesture transactions ----
    //
    // While a finger drags, the UI calls [preview] with ONE command that describes the whole gesture so far (e.g.
    // MoveClips(ids, totalDelta)). It is applied to the committed state, the result becomes what the preview and the
    // timeline show, and NOTHING else happens: no history entry, no undo merge, no autosave, no persistence. When the
    // finger lifts, [commit] records exactly one undo step; [cancelPreview] restores the committed state. Because every
    // preview is computed from the committed state, a cancelled or failed gesture can never leave residue.

    @Volatile
    private var transientActive = false

    /** True between the first [preview] and the matching [commit]/[cancelPreview] (or any other edit). */
    val inTransaction: Boolean get() = transientActive

    /** The committed state (never the transient preview); this is what is saved. */
    val committedState: EditorState get() = history.state

    /** Shows [command] applied to the committed state without recording it. Throws [MlException]; state unchanged then. */
    @Synchronized
    fun preview(command: Command): EditorState {
        val next = try {
            command.apply(history.state)
        } catch (e: IllegalArgumentException) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, e.message ?: "Invalid edit", e)
        }
        current = next
        transientActive = true
        for (l in listeners) l(current)
        return current
    }

    /** Ends the transaction by recording [command] (normally the last previewed one) as ONE undo step. */
    @Synchronized
    fun commit(command: Command): EditorState {
        try {
            return execute(command)
        } catch (e: MlException) {
            cancelPreview()
            throw e
        } finally {
            history.breakMerge()
        }
    }

    /** Drops the preview and shows the committed state again. */
    @Synchronized
    fun cancelPreview() {
        if (!transientActive) return
        publish()
    }

    // ---- editing ----

    /** Applies [command]; on failure the state is untouched and the [MlException] is rethrown. */
    @Synchronized
    fun execute(command: Command): EditorState {
        try {
            history.execute(command)
        } catch (e: IllegalArgumentException) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, e.message ?: "Invalid edit", e)
        }
        clampPlayhead()
        publish()
        return current
    }

    @Synchronized
    fun undo(): EditorState {
        history.undo()
        clampPlayhead()
        publish()
        return current
    }

    @Synchronized
    fun redo(): EditorState {
        history.redo()
        clampPlayhead()
        publish()
        return current
    }

    /** Ends the current drag so the next one is a separate undo step. */
    @Synchronized
    fun endGesture() = history.breakMerge()

    @Synchronized
    fun select(selection: Selection) {
        history.replaceState(history.state.withSelection(selection))
        publish()
    }

    @Synchronized
    fun clearSelection() = select(Selection.EMPTY)

    fun newId(kind: String): String = ids.next(kind)

    private fun clampPlayhead() {
        val d = current.timeline.durationUs
        if (clock.currentTimeUs() > d) clock.seekTo(d)
    }

    // ---- playback (the one playhead) ----

    val currentTimeUs: Long get() = clock.currentTimeUs().coerceAtMost(timeline.durationUs)
    val isPlaying: Boolean get() = clock.isPlaying

    fun seekTo(timeUs: Long) = clock.seekTo(timeUs.coerceIn(0, timeline.durationUs))

    fun play() {
        val d = timeline.durationUs
        if (d <= 0) return
        if (clock.currentTimeUs() >= d) clock.seekTo(0)
        clock.play()
    }

    fun pause() = clock.pause()

    /** Resolves what is on screen at the playhead. Stops playback when the end is reached. */
    fun frame(): SceneFrame {
        val d = timeline.durationUs
        var t = clock.currentTimeUs()
        if (t >= d) {
            t = d
            if (clock.isPlaying) {
                clock.pause()
                clock.seekTo(d)
            }
        }
        return SceneEvaluator.evaluate(timeline, t)
    }

    fun frameAt(timeUs: Long): SceneFrame = SceneEvaluator.evaluate(timeline, timeUs)

    // ---- high-level operations used by the UI ----

    fun addTrack(kind: TrackKind, name: String? = null): String {
        val id = ids.next("track")
        val n = timeline.tracks.count { it.kind == kind } + 1
        execute(AddTrack(Track(id, name ?: "${kind.label} $n", kind)))
        return id
    }

    /** Adds a clip at [startUs] on [trackId] (or at the first free spot after it). */
    fun addClip(trackId: String, startUs: Long, durationUs: Long, assetId: String? = null, payload: Map<String, String> = emptyMap(), sourceDurationUs: Long? = null): String {
        val free = timeline.track(trackId).firstFreeStart(startUs, durationUs)
        val id = ids.next("clip")
        execute(AddClip(trackId, MediaClip(id, free, durationUs, assetId, payload = payload, sourceDurationUs = sourceDurationUs)))
        return id
    }

    fun splitAtPlayhead(): Boolean {
        val t = currentTimeUs
        val targets = selection.clipIds.ifEmpty {
            timeline.tracks.filter { !it.locked }.mapNotNull { it.clipAt(t)?.id }.toSet()
        }.filter { id -> timeline.findClip(id)?.second?.let { t > it.startUs && t < it.endUs } == true }
        if (targets.isEmpty()) return false
        execute(CompositeCommand("Split clip", targets.map { SplitClip(it, t, ids.next("clip")) }))
        return true
    }

    fun deleteSelection(): Boolean {
        val sel = selection
        return when {
            sel.keyframes.isNotEmpty() -> { execute(DeleteKeyframes(sel.keyframes.toList())); true }
            sel.clipIds.isNotEmpty() -> { execute(DeleteClips(sel.clipIds.toList())); true }
            else -> false
        }
    }

    fun duplicateSelection(): Boolean {
        val sel = selection
        return when {
            sel.keyframes.isNotEmpty() -> {
                val refs = sel.keyframes.toList()
                execute(DuplicateKeyframes(refs, KEYFRAME_DUPLICATE_OFFSET_US, refs.map { KeyframeIds.next() }))
                true
            }
            sel.clipIds.isNotEmpty() -> {
                val list = sel.clipIds.toList()
                execute(DuplicateClips(list, list.map { ids.next("clip") }))
                true
            }
            else -> false
        }
    }

    fun copySelection(): Boolean {
        val sel = selection
        return when {
            sel.keyframes.isNotEmpty() -> { clipboardKeyframes = ClipboardKeyframes.copy(timeline, sel.keyframes); clipboardClips = null; true }
            sel.clipIds.isNotEmpty() -> { clipboardClips = ClipboardClips.copy(timeline, sel.clipIds); clipboardKeyframes = null; true }
            else -> false
        }
    }

    /**
     * Pastes whatever was copied last at the playhead: clips go to [targetTrackId] (default: the track of the first
     * selected clip), keyframes go to the first selected clip / layer ([keyframeOwner]).
     */
    fun paste(targetTrackId: String? = null, keyframeOwner: AnimationOwner? = null): Boolean {
        val kfs = clipboardKeyframes
        if (keyframeOwner != null && kfs != null && !kfs.isEmpty) {
            val local = when (keyframeOwner) {
                is AnimationOwner.ClipOwner -> currentTimeUs - timeline.requireClip(keyframeOwner.clipId).second.startUs
                is AnimationOwner.LayerOwner -> currentTimeUs
            }
            execute(PasteKeyframes(kfs, keyframeOwner, local, kfs.entries.map { KeyframeIds.next() }))
            return true
        }
        val clips = clipboardClips
        if (clips == null || clips.isEmpty) return false
        val track = targetTrackId
            ?: selection.clipIds.firstNotNullOfOrNull { timeline.findClip(it)?.first?.id }
            ?: timeline.tracks.firstOrNull { it.kind.hasVisuals == clips.entries.first().kind.hasVisuals }?.id
            ?: return false
        execute(PasteClips(clips, currentTimeUs, track, clips.entries.map { ids.next("clip") }))
        return true
    }

    /** Owner-local time of the playhead (clip-local for clips, timeline time for layers). */
    fun localTime(owner: AnimationOwner): Long = when (owner) {
        is AnimationOwner.ClipOwner -> currentTimeUs - timeline.requireClip(owner.clipId).second.startUs
        is AnimationOwner.LayerOwner -> currentTimeUs
    }

    /** Evaluated value of a property at the playhead. */
    fun valueAtPlayhead(owner: AnimationOwner, property: String): Double =
        animOf(timeline, owner).properties.valueAt(property, localTime(owner), PropertyRegistry.defaultOf(property))

    /** The stopwatch: adds a keyframe at the playhead holding the property's current value (or updates the existing one). */
    fun addKeyframeAtPlayhead(owner: AnimationOwner, property: String, interpolation: Interpolation = Interpolation.Linear): String {
        val t = localTime(owner)
        val existing = animOf(timeline, owner).properties.track(property).at(t)
        if (existing != null) return existing.id
        val kf = Keyframe(t, valueAtPlayhead(owner, property), interpolation)
        execute(SetKeyframe(owner, property, kf))
        return kf.id
    }

    /** Edits a property at the playhead (auto-key when it is animated). */
    fun setValueAtPlayhead(owner: AnimationOwner, property: String, value: Double) {
        execute(SetPropertyValue(owner, property, localTime(owner), value))
    }

    fun previousKeyframeTimeUs(owner: AnimationOwner): Long? {
        val base = baseOf(owner)
        val t = localTime(owner)
        return animOf(timeline, owner).properties.tracks.values.mapNotNull { it.previousBefore(t)?.timeUs }.maxOrNull()?.plus(base)
    }

    fun nextKeyframeTimeUs(owner: AnimationOwner): Long? {
        val base = baseOf(owner)
        val t = localTime(owner)
        return animOf(timeline, owner).properties.tracks.values.mapNotNull { it.nextAfter(t)?.timeUs }.minOrNull()?.plus(base)
    }

    private fun baseOf(owner: AnimationOwner): Long = when (owner) {
        is AnimationOwner.ClipOwner -> timeline.requireClip(owner.clipId).second.startUs
        is AnimationOwner.LayerOwner -> 0L
    }

    fun applyPreset(owner: AnimationOwner, presetId: String) {
        execute(ApplyPreset(owner, presetId, canvasWidth, canvasHeight))
    }

    /** Animation data (keyframes, motion path, presets) of a clip or layer; throws NOT_FOUND if it no longer exists. */
    fun anim(owner: AnimationOwner): AnimData = animOf(timeline, owner)

    fun exists(owner: AnimationOwner): Boolean = when (owner) {
        is AnimationOwner.ClipOwner -> timeline.findClip(owner.clipId) != null
        is AnimationOwner.LayerOwner -> timeline.findLayer(owner.layerId) != null
    }

    fun displayName(owner: AnimationOwner): String = when (owner) {
        is AnimationOwner.ClipOwner -> timeline.findClip(owner.clipId)?.let { (t, c) -> c.payload["text"] ?: c.assetId ?: "${t.name} clip" } ?: "?"
        is AnimationOwner.LayerOwner -> timeline.findLayer(owner.layerId)?.name ?: "?"
    }

    /** Time of the owner's time base zero on the timeline (clip start / 0 for layers). */
    fun baseUs(owner: AnimationOwner): Long = baseOf(owner)

    fun presetsOf(owner: AnimationOwner): List<AppliedPreset> = animOf(timeline, owner).presets

    // ---- project persistence ----

    /** The document with this session's timeline written into it (tracks, layers, clips, keyframes, paths, presets). */
    fun toDocument(base: ProjectDocument, nowMs: Long = System.currentTimeMillis()): ProjectDocument =
        TimelineMapper.applyTo(base, history.state.timeline).copy(modifiedAtMs = nowMs, assets = assetList)

    /** Replaces the timeline (project load). Undo history starts fresh. */
    @Synchronized
    fun load(document: ProjectDocument) {
        history.replaceState(EditorState(TimelineMapper.toTimeline(document)), clearHistory = true)
        assetList = document.assets
        assetsVersion++
        clock.seekTo(0)
        publish()
    }

    companion object {
        /** A session for a loaded project: canvas size and frame rate come from its config. */
        fun fromDocument(document: ProjectDocument, ids: IdSource = IdSource.RANDOM, clock: TimelineClock = TimelineClock()) =
            EditorSession(
                TimelineMapper.toTimeline(document),
                document.config.width, document.config.height, document.config.frameRate, ids, clock, document.assets,
            )

        /** Offset applied to duplicated keyframes so the copies do not sit on top of the originals. */
        const val KEYFRAME_DUPLICATE_OFFSET_US = 500_000L
    }
}
