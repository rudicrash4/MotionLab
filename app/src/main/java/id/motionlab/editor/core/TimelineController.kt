package id.motionlab.editor.core

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Pusat logika penyuntingan timeline: tambah / pindah / trim / split / duplikat / hapus klip,
 * urutan layer, keyframe, transisi, serta undo/redo. Project bersifat immutable sehingga
 * undo cukup menyimpan snapshot.
 *
 * Semua fungsi dipanggil dari thread utama. [project] aman dibaca dari thread mana pun (thread GL).
 */
class TimelineController {

    private val _project = MutableStateFlow(Project())
    val project: StateFlow<Project> = _project.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _undoCount = MutableStateFlow(0)
    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()
    private val _redoCount = MutableStateFlow(0)
    val redoCount: StateFlow<Int> = _redoCount.asStateFlow()

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private var coalesceKey: String? = null
    private var coalesceAt = 0L

    companion object {
        const val MIN_DURATION_MS = 100L
        const val KEYFRAME_TOLERANCE_MS = 20L
        private const val MAX_UNDO = 80
        private const val IMAGE_DEFAULT_MS = 3000L
    }

    // ---------------------------------------------------------------- dasar

    fun load(p: Project) {
        undoStack.clear()
        redoStack.clear()
        coalesceKey = null
        _project.value = p
        _selectedClipId.value = null
        publishCounts()
    }

    fun select(clipId: String?) {
        _selectedClipId.value = clipId
    }

    private fun publishCounts() {
        _undoCount.value = undoStack.size
        _redoCount.value = redoStack.size
    }

    private fun pushUndo() {
        undoStack.addLast(_project.value)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        publishCounts()
    }

    /** Dipanggil di awal gestur kontinu di timeline (drag / trim) agar seluruh gestur = 1 langkah undo. */
    fun beginGesture() {
        coalesceKey = null
        pushUndo()
    }

    private fun commit(p: Project, push: Boolean = true, coalesce: String? = null) {
        if (p == _project.value) return
        if (push) {
            val now = System.currentTimeMillis()
            val same = coalesce != null && coalesce == coalesceKey && now - coalesceAt < 800L
            if (!same) pushUndo()
            coalesceKey = coalesce
            coalesceAt = now
        }
        _project.value = p
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_project.value)
        _project.value = undoStack.removeLast()
        coalesceKey = null
        fixSelection()
        publishCounts()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_project.value)
        _project.value = redoStack.removeLast()
        coalesceKey = null
        fixSelection()
        publishCounts()
    }

    private fun fixSelection() {
        val sel = _selectedClipId.value
        if (sel != null && _project.value.findClip(sel) == null) _selectedClipId.value = null
    }

    private fun mapClip(p: Project, id: String, f: (Clip) -> Clip): Project =
        p.copy(tracks = p.tracks.map { t ->
            if (t.clips.any { it.id == id }) t.copy(clips = t.clips.map { c -> if (c.id == id) f(c) else c }) else t
        })

    private fun isFree(track: Track, start: Long, dur: Long, excludeId: String?): Boolean =
        track.clips.none { it.id != excludeId && start < it.endMs && start + dur > it.startMs }

    // ---------------------------------------------------------------- proyek

    fun updateProject(f: (Project) -> Project) {
        commit(f(_project.value))
    }

    // ---------------------------------------------------------------- tambah klip

    /** Menambahkan klip ke sistem layer terpadu. Setiap klip menempati layernya sendiri. */
    fun addClip(clip: Clip, atIndex: Int = -1): String {
        val p = _project.value
        val count = p.tracks.count { it.type == clip.type } + 1
        val layerName = clip.name.ifEmpty { trackLabel(clip.type) + " " + count }
        val track = Track(
            id = UUID.randomUUID().toString(),
            type = clip.type,
            name = layerName,
            clips = listOf(clip.copy(name = layerName))
        )
        val list = p.tracks.toMutableList()
        val insertIdx = if (atIndex in 0..list.size) atIndex else 0
        list.add(insertIdx, track)
        commit(p.copy(tracks = list))
        _selectedClipId.value = clip.id
        return clip.id
    }

    private fun trackLabel(t: TrackType): String = when (t) {
        TrackType.VIDEO -> "Video"
        TrackType.AUDIO -> "Audio"
        TrackType.IMAGE -> "Gambar"
        TrackType.TEXT -> "Teks"
        TrackType.SHAPE -> "Bentuk"
    }

    fun addMedia(type: TrackType, name: String, uri: String, sourceDurationMs: Long, atMs: Long): String {
        val dur = if (sourceDurationMs > 0L) sourceDurationMs else IMAGE_DEFAULT_MS
        return addClip(
            Clip(
                type = type, name = name, uri = uri, startMs = atMs, durationMs = dur,
                sourceDurationMs = if (type == TrackType.VIDEO || type == TrackType.AUDIO) sourceDurationMs else 0L
            )
        )
    }

    fun addText(text: String, atMs: Long): String =
        addClip(
            Clip(
                type = TrackType.TEXT, name = text.take(18), startMs = atMs, durationMs = IMAGE_DEFAULT_MS,
                text = text
            )
        )

    fun addShape(kind: ShapeKind, atMs: Long = 0L): String =
        addClip(
            Clip(
                type = TrackType.SHAPE, name = kind.name.lowercase().replaceFirstChar { it.uppercase() },
                startMs = atMs, durationMs = IMAGE_DEFAULT_MS, shape = kind
            )
        )

    fun addShape(kind: ShapeKind, color: Int, atMs: Long = 0L): String =
        addClip(
            Clip(
                type = TrackType.SHAPE, name = kind.name.lowercase().replaceFirstChar { it.uppercase() },
                startMs = atMs, durationMs = IMAGE_DEFAULT_MS, shape = kind, fillColor = color
            )
        )

    fun addSolid(color: Int, atMs: Long = 0L): String =
        addClip(
            Clip(
                type = TrackType.SHAPE, name = "Solid",
                startMs = atMs, durationMs = IMAGE_DEFAULT_MS, shape = ShapeKind.RECT, fillColor = color
            )
        )

    // ---------------------------------------------------------------- pindah / trim / split

    /**
     * Memindahkan klip (dipanggil terus-menerus saat drag; panggil [beginGesture] sekali di awal).
     * Mengembalikan (startAktual, indexTrackAktual) atau null bila tidak memungkinkan.
     */
    fun moveClip(clipId: String, desiredStart: Long, targetTrackIndex: Int): Pair<Long, Int>? {
        val p = _project.value
        val srcIdx = p.trackIndexOfClip(clipId)
        if (srcIdx < 0) return null
        val srcTrack = p.tracks[srcIdx]
        if (srcTrack.locked) return null
        val clip = srcTrack.clips.first { it.id == clipId }

        fun place(dstIdx: Int): Long? {
            val others = p.tracks[dstIdx].clips.filter { it.id != clipId }.sortedBy { it.startMs }
            val center = desiredStart + clip.durationMs / 2
            val prev = others.lastOrNull { it.startMs + it.durationMs / 2 < center }
            val next = others.firstOrNull { it.startMs + it.durationMs / 2 >= center }
            val lo = prev?.endMs ?: 0L
            val hi = next?.startMs ?: Long.MAX_VALUE
            if (hi != Long.MAX_VALUE && hi - lo < clip.durationMs) return null
            val maxStart = if (hi == Long.MAX_VALUE) Long.MAX_VALUE else hi - clip.durationMs
            return desiredStart.coerceIn(lo, maxStart)
        }

        var dst = srcIdx
        if (targetTrackIndex in p.tracks.indices && targetTrackIndex != srcIdx) {
            val tt = p.tracks[targetTrackIndex]
            if (tt.type == clip.type && !tt.locked) dst = targetTrackIndex
        }
        var start = place(dst)
        if (start == null && dst != srcIdx) {
            dst = srcIdx
            start = place(dst)
        }
        if (start == null) return null

        val moved = clip.copy(startMs = start)
        val np = p.copy(tracks = p.tracks.mapIndexed { i, t ->
            when {
                i == srcIdx && i == dst -> t.copy(clips = t.clips.map { if (it.id == clipId) moved else it }.sortedBy { it.startMs })
                i == srcIdx -> t.copy(clips = t.clips.filter { it.id != clipId })
                i == dst -> t.copy(clips = (t.clips + moved).sortedBy { it.startMs })
                else -> t
            }
        })
        commit(np, push = false)
        return Pair(start, dst)
    }

    fun trimStart(clipId: String, newStart: Long) {
        val p = _project.value
        val ti = p.trackIndexOfClip(clipId)
        if (ti < 0 || p.tracks[ti].locked) return
        val track = p.tracks[ti]
        val c = track.clips.first { it.id == clipId }
        val prevEnd = track.clips.filter { it.id != clipId && it.endMs <= c.startMs }.maxOfOrNull { it.endMs } ?: 0L
        var ns = newStart
        if (ns < prevEnd) ns = prevEnd
        val latest = c.endMs - MIN_DURATION_MS
        if (ns > latest) ns = latest
        val isSource = c.sourceDurationMs > 0L
        if (isSource) {
            // Tidak boleh mundur melewati awal file sumber (memperhitungkan kurva kecepatan).
            val back = c.speedMap.timeForOffset(-c.sourceInMs.toDouble())
            val earliest = c.startMs + Math.ceil(back).toLong()
            if (ns < earliest) ns = earliest
        }
        val delta = ns - c.startMs
        if (delta == 0L) return
        val newSourceIn = if (isSource) {
            (c.sourceInMs + Math.round(c.speedMap.offset(delta.toDouble()))).coerceAtLeast(0L)
        } else c.sourceInMs
        val nc = c.copy(
            startMs = ns,
            durationMs = c.durationMs - delta,
            sourceInMs = newSourceIn,
            keyframes = shiftKeyframesForTrim(c, delta)
        )
        commit(mapClip(p, clipId) { nc }, push = false)
    }

    fun trimEnd(clipId: String, newEnd: Long) {
        val p = _project.value
        val ti = p.trackIndexOfClip(clipId)
        if (ti < 0 || p.tracks[ti].locked) return
        val track = p.tracks[ti]
        val c = track.clips.first { it.id == clipId }
        var maxEnd = track.clips.filter { it.id != clipId && it.startMs >= c.endMs }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE
        if (c.sourceDurationMs > 0L) {
            // Sisa sumber dikonversi ke waktu timeline lewat invers integral kecepatan.
            val avail = (c.sourceDurationMs - c.sourceInMs).toDouble()
            val bySource = c.startMs + Math.floor(c.speedMap.timeForOffset(avail)).toLong()
            if (bySource < maxEnd) maxEnd = bySource
        }
        val minEnd = c.startMs + MIN_DURATION_MS
        if (maxEnd < minEnd) maxEnd = minEnd
        var ne = newEnd
        if (ne < minEnd) ne = minEnd
        if (ne > maxEnd) ne = maxEnd
        val dur = ne - c.startMs
        if (dur == c.durationMs) return
        commit(mapClip(p, clipId) { it.copy(durationMs = dur) }, push = false)
    }

    /** Memotong klip pada waktu timeline [t]. Mengembalikan id klip bagian kanan, atau null. */
    fun splitAt(clipId: String, t: Long): String? {
        val p = _project.value
        val ti = p.trackIndexOfClip(clipId)
        if (ti < 0 || p.tracks[ti].locked) return null
        val c = p.tracks[ti].clips.first { it.id == clipId }
        if (t <= c.startMs + MIN_DURATION_MS || t >= c.endMs - MIN_DURATION_MS) return null
        val local = t - c.startMs
        val leftKf = HashMap<String, KeyframeTrack>()
        val rightKf = HashMap<String, KeyframeTrack>()
        for ((key, kt) in c.keyframes) {
            val (l, r) = kt.split(local, c.baseValue(key))
            leftKf[key] = l
            rightKf[key] = r
        }
        val left = c.copy(durationMs = local, keyframes = leftKf, transitionOut = Transition())
        val right = c.copy(
            id = UUID.randomUUID().toString(),
            name = c.name + " (2)",
            startMs = t,
            durationMs = c.endMs - t,
            sourceInMs = if (c.sourceDurationMs > 0L) c.sourceInMs + Math.round(c.speedMap.offset(local.toDouble())) else c.sourceInMs,
            keyframes = rightKf,
            transitionIn = Transition()
        )
        val leftTrack = p.tracks[ti].copy(clips = listOf(left))
        val rightTrack = Track(
            id = UUID.randomUUID().toString(),
            type = right.type,
            name = right.name,
            clips = listOf(right)
        )
        val list = p.tracks.toMutableList()
        list[ti] = leftTrack
        list.add(ti, rightTrack)
        commit(p.copy(tracks = list))
        _selectedClipId.value = right.id
        return right.id
    }

    fun deleteClip(clipId: String) {
        val p = _project.value
        val ti = p.trackIndexOfClip(clipId)
        if (ti < 0 || p.tracks[ti].locked) return
        val np = p.copy(
            tracks = p.tracks
                .map { t -> if (t.clips.any { it.id == clipId }) t.copy(clips = t.clips.filter { it.id != clipId }) else t }
                .filter { it.clips.isNotEmpty() }
        )
        commit(np)
        if (_selectedClipId.value == clipId) _selectedClipId.value = null
    }

    fun duplicateClip(clipId: String) {
        val p = _project.value
        val ti = p.trackIndexOfClip(clipId)
        if (ti < 0) return
        val c = p.findClip(clipId) ?: return
        val copyClip = c.copy(
            id = UUID.randomUUID().toString(),
            name = c.name + " (Salin)",
            startMs = c.endMs
        )
        val copyTrack = Track(
            id = UUID.randomUUID().toString(),
            type = copyClip.type,
            name = copyClip.name,
            clips = listOf(copyClip)
        )
        val list = p.tracks.toMutableList()
        list.add(ti, copyTrack)
        commit(p.copy(tracks = list))
        _selectedClipId.value = copyClip.id
    }

    // ---- Kontrol Volume & Mute Klip ----
    fun setClipVolume(clipId: String, volume: Float) {
        val p = _project.value
        commit(mapClip(p, clipId) { it.copy(volume = volume.coerceIn(0f, 2f)) }, push = true, coalesce = "vol:$clipId")
    }

    fun toggleClipMuted(clipId: String) {
        val p = _project.value
        commit(mapClip(p, clipId) { it.copy(isMuted = !it.isMuted) })
    }

    // ---- Reverse Playback Video ----
    fun toggleClipReversed(clipId: String) {
        val p = _project.value
        commit(mapClip(p, clipId) { it.copy(isReversed = !it.isReversed) })
    }

    // ---- Ubah Rasio Aspek Kanvas ----
    fun changeAspectRatio(aspectW: Int, aspectH: Int) {
        val p = _project.value
        if (p.aspectW == aspectW && p.aspectH == aspectH) return
        commit(p.copy(aspectW = aspectW, aspectH = aspectH, updatedAt = System.currentTimeMillis()))
    }

    // ---- Reorder Layer ----
    fun reorderTrack(fromIndex: Int, toIndex: Int) {
        val p = _project.value
        if (fromIndex !in p.tracks.indices || toIndex !in p.tracks.indices || fromIndex == toIndex) return
        val list = p.tracks.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        commit(p.copy(tracks = list))
    }

    // ---- Clipboard Layer & Keyframe ----
    private var clipboardClip: Clip? = null
    private var clipboardKeyframes: Map<String, KeyframeTrack>? = null

    fun copyClip(clipId: String) {
        val c = _project.value.findClip(clipId) ?: return
        clipboardClip = c
    }

    fun pasteClip(atMs: Long): String? {
        val c = clipboardClip ?: return null
        val copyClip = c.copy(
            id = UUID.randomUUID().toString(),
            name = c.name + " (Tempel)",
            startMs = atMs
        )
        return addClip(copyClip)
    }

    fun canPasteClip(): Boolean = clipboardClip != null

    fun copyKeyframes(clipId: String) {
        val c = _project.value.findClip(clipId) ?: return
        clipboardKeyframes = c.keyframes
    }

    fun pasteKeyframes(clipId: String) {
        val kfs = clipboardKeyframes ?: return
        val p = _project.value
        commit(mapClip(p, clipId) { it.copy(keyframes = kfs) })
    }

    fun canPasteKeyframes(): Boolean = clipboardKeyframes?.isNotEmpty() == true

    /** Keyframe setelah trim awal sebesar [delta] ms (delta>0 memotong, delta<0 memperpanjang). */
    private fun shiftKeyframesForTrim(c: Clip, delta: Long): Map<String, KeyframeTrack> {
        if (delta <= 0L) return c.keyframes.mapValues { it.value.shifted(-delta) }
        val out = HashMap<String, KeyframeTrack>()
        for ((key, kt) in c.keyframes) out[key] = kt.split(delta, c.baseValue(key)).second
        return out
    }

    /**
     * Kecepatan KONSTAN untuk seluruh klip. Potongan sumber yang dipakai tetap sama, sehingga durasi
     * di timeline berubah (seperti tombol "speed" biasa). Kurva Speed Ramp yang ada dihapus.
     */
    fun setSpeed(clipId: String, newSpeed: Float) {
        val p = _project.value
        val ti = p.trackIndexOfClip(clipId)
        if (ti < 0) return
        val track = p.tracks[ti]
        val c = track.clips.first { it.id == clipId }
        if (c.type != TrackType.VIDEO && c.type != TrackType.AUDIO) return
        val s = SpeedMap.clampSpeed(newSpeed)
        val srcSpan = c.speedMap.offset(c.durationMs.toDouble())
        var newDur = Math.round(srcSpan / s)
        val nextStart = track.clips.filter { it.id != clipId && it.startMs >= c.endMs }.minOfOrNull { it.startMs }
        if (nextStart != null && c.startMs + newDur > nextStart) newDur = nextStart - c.startMs
        if (newDur < MIN_DURATION_MS) newDur = MIN_DURATION_MS
        val factor = newDur.toFloat() / c.durationMs.toFloat()
        commit(
            mapClip(p, clipId) {
                val kf = HashMap<String, KeyframeTrack>()
                for ((k, v) in it.keyframes) if (k != Props.SPEED) kf[k] = v.scaled(factor)
                it.copy(props = it.props + (Props.SPEED to s), durationMs = newDur, keyframes = kf)
            },
            coalesce = "speed:$clipId"
        )
    }

    /**
     * Mengganti kurva Speed Ramp (waktu titik = waktu lokal klip). Daftar kosong = kembali ke kecepatan
     * konstan (nilai dasar). Durasi klip tidak berubah, kecuali sumber tidak cukup untuk kurva baru:
     * maka klip dipendekkan sampai sumber habis. Mengembalikan true bila durasi dipendekkan.
     */
    fun setSpeedFrames(clipId: String, frames: List<Keyframe>, coalesce: String? = null): Boolean {
        val p = _project.value
        val c = p.findClip(clipId) ?: return false
        if (c.type != TrackType.VIDEO && c.type != TrackType.AUDIO) return false
        val ti = p.trackIndexOfClip(clipId)
        if (p.tracks[ti].locked) return false
        val norm = SpeedEdit.normalize(frames)
        val (nc, shortened) = clampToSource(
            c.copy(keyframes = if (norm.isEmpty()) c.keyframes - Props.SPEED else c.keyframes + (Props.SPEED to KeyframeTrack(norm)))
        )
        commit(mapClip(p, clipId) { nc }, coalesce = coalesce ?: "speedcurve:$clipId")
        return shortened
    }

    /** Memastikan klip tidak memakai sumber lebih dari yang tersedia; durasi dipendekkan bila perlu. */
    private fun clampToSource(c: Clip): Pair<Clip, Boolean> {
        if (c.sourceDurationMs <= 0L) return Pair(c, false)
        val avail = (c.sourceDurationMs - c.sourceInMs).toDouble()
        val map = c.speedMap
        if (map.offset(c.durationMs.toDouble()) <= avail + 0.5) return Pair(c, false)
        val nd = Math.floor(map.timeForOffset(avail)).toLong().coerceAtLeast(MIN_DURATION_MS)
        return Pair(c.copy(durationMs = nd), true)
    }

    /** Kembali ke 1x tanpa kurva; durasi timeline tidak berubah (dipendekkan hanya bila sumber tidak cukup). */
    fun resetSpeed(clipId: String): Boolean {
        val p = _project.value
        val c = p.findClip(clipId) ?: return false
        if (c.type != TrackType.VIDEO && c.type != TrackType.AUDIO) return false
        val ti = p.trackIndexOfClip(clipId)
        if (p.tracks[ti].locked) return false
        val (nc, shortened) = clampToSource(c.copy(props = c.props + (Props.SPEED to 1f), keyframes = c.keyframes - Props.SPEED))
        commit(mapClip(p, clipId) { nc })
        return shortened
    }

    /** Menerapkan preset Speed Ramp. */
    fun applySpeedPreset(clipId: String, preset: SpeedPreset): Boolean {
        val c = _project.value.findClip(clipId) ?: return false
        val existing = c.keyframes[Props.SPEED]?.frames ?: emptyList()
        return setSpeedFrames(clipId, preset.frames(c.durationMs, existing, c.baseValue(Props.SPEED)), "speedpreset:$clipId")
    }

    // ---------------------------------------------------------------- efek

    fun toggleFx(clipId: String, key: String) {
        commit(mapClip(_project.value, clipId) { c ->
            c.copy(fxDisabled = if (c.fxDisabled.contains(key)) c.fxDisabled - key else c.fxDisabled + key)
        })
    }

    /** Menggeser posisi sebuah warp (wave/swirl/displace) dalam urutan render. */
    fun moveWarp(clipId: String, key: String, delta: Int) {
        commit(mapClip(_project.value, clipId) { c ->
            val order = c.warpOrder().map { listOf(Props.WAVE, Props.SWIRL, Props.DISPLACE)[it.toInt()] }.toMutableList()
            val i = order.indexOf(key)
            val j = i + delta
            if (i < 0 || j !in order.indices) c else {
                order.removeAt(i)
                order.add(j, key)
                c.copy(fxOrder = order)
            }
        })
    }

    fun captureEffects(clipId: String): EffectSet? = _project.value.findClip(clipId)?.let { Effects.capture(it) }

    fun applyEffects(clipId: String, set: EffectSet) {
        commit(mapClip(_project.value, clipId) { Effects.apply(it, set) })
    }

    fun applyEffectPreset(clipId: String, preset: EffectPreset) {
        commit(mapClip(_project.value, clipId) { Effects.applyPreset(it, preset) })
    }

    fun clearEffects(clipId: String) {
        commit(mapClip(_project.value, clipId) { Effects.clear(it) })
    }

    fun toggleTrackSolo(trackId: String) = updateTrack(trackId) { it.copy(solo = !it.solo) }

    // ---------------------------------------------------------------- track / layer

    fun moveTrack(from: Int, to: Int) {
        val p = _project.value
        if (from !in p.tracks.indices || to !in p.tracks.indices || from == to) return
        val list = p.tracks.toMutableList()
        val t = list.removeAt(from)
        list.add(to, t)
        commit(p.copy(tracks = list), push = false)
    }

    fun moveTrackOfClip(clipId: String, delta: Int) {
        val i = _project.value.trackIndexOfClip(clipId)
        if (i < 0) return
        val to = i + delta
        if (to !in _project.value.tracks.indices) return
        pushUndo()
        moveTrack(i, to)
    }

    fun toggleTrackMuted(trackId: String) = updateTrack(trackId) { it.copy(muted = !it.muted) }
    fun toggleTrackHidden(trackId: String) = updateTrack(trackId) { it.copy(hidden = !it.hidden) }
    fun toggleTrackLocked(trackId: String) = updateTrack(trackId) { it.copy(locked = !it.locked) }

    private fun updateTrack(trackId: String, f: (Track) -> Track) {
        val p = _project.value
        commit(p.copy(tracks = p.tracks.map { if (it.id == trackId) f(it) else it }))
    }

    // ---------------------------------------------------------------- properti & keyframe

    fun updateClip(clipId: String, coalesce: String? = null, f: (Clip) -> Clip) {
        commit(mapClip(_project.value, clipId, f), coalesce = coalesce)
    }

    /**
     * Mengubah nilai properti. Jika properti sudah punya keyframe, nilai disimpan sebagai keyframe
     * di [localMs] (auto-keyframe seperti Alight Motion); jika tidak, mengubah nilai dasar.
     */
    fun setProperty(clipId: String, key: String, value: Float, localMs: Long) {
        setProperties(clipId, mapOf(key to value), localMs, "prop:$clipId:$key")
    }

    /** Mengubah beberapa properti sekaligus dalam satu langkah (gestur transform langsung di preview). */
    fun setProperties(clipId: String, values: Map<String, Float>, localMs: Long, coalesce: String) {
        commit(
            mapClip(_project.value, clipId) { c ->
                var out = c
                for ((key, v) in values) out = withProp(out, key, v, localMs)
                out
            },
            coalesce = coalesce
        )
    }

    private fun withProp(c: Clip, key: String, value: Float, localMs: Long): Clip {
        val def = Props.get(key) ?: return c
        val v = value.coerceIn(def.min, def.max)
        if (!c.hasKeyframes(key)) return c.copy(props = c.props + (key to v))
        val kt = c.keyframes[key] ?: KeyframeTrack()
        val idx = kt.indexAt(localMs, KEYFRAME_TOLERANCE_MS)
        val nk = if (idx >= 0) {
            KeyframeTrack(kt.frames.mapIndexed { i, k -> if (i == idx) k.copy(value = v) else k })
        } else {
            kt.upsert(Keyframe(localMs.coerceIn(0L, c.durationMs), v))
        }
        return c.copy(keyframes = c.keyframes + (key to nk))
    }

    fun toggleKeyframe(clipId: String, key: String, localMs: Long) {
        commit(
            mapClip(_project.value, clipId) { c ->
                val kt = c.keyframes[key] ?: KeyframeTrack()
                val idx = kt.indexAt(localMs, KEYFRAME_TOLERANCE_MS)
                if (idx >= 0) {
                    val removed = kt.frames[idx]
                    val rest = kt.removeAt(idx)
                    if (rest.frames.isEmpty()) {
                        c.copy(keyframes = c.keyframes - key, props = c.props + (key to removed.value))
                    } else {
                        c.copy(keyframes = c.keyframes + (key to rest))
                    }
                } else {
                    val t = localMs.coerceIn(0L, c.durationMs)
                    val current = c.valueAt(key, t)
                    c.copy(keyframes = c.keyframes + (key to kt.upsert(Keyframe(t, current))))
                }
            }
        )
    }

    fun setKeyframeCurve(
        clipId: String, key: String, localMs: Long, interp: Interp,
        bx1: Float = 0.42f, by1: Float = 0f, bx2: Float = 0.58f, by2: Float = 1f
    ) {
        commit(
            mapClip(_project.value, clipId) { c ->
                val kt = c.keyframes[key] ?: return@mapClip c
                val idx = kt.indexAt(localMs, KEYFRAME_TOLERANCE_MS)
                if (idx < 0) return@mapClip c
                val nk = KeyframeTrack(kt.frames.mapIndexed { i, k ->
                    if (i == idx) k.copy(interp = interp, bx1 = bx1, by1 = by1, bx2 = bx2, by2 = by2) else k
                })
                c.copy(keyframes = c.keyframes + (key to nk))
            },
            coalesce = "curve:$clipId:$key"
        )
    }

    fun removeAllKeyframes(clipId: String, key: String, localMs: Long) {
        commit(
            mapClip(_project.value, clipId) { c ->
                if (!c.hasKeyframes(key)) c
                else c.copy(keyframes = c.keyframes - key, props = c.props + (key to c.valueAt(key, localMs)))
            }
        )
    }

    /** Mengganti seluruh keyframe satu properti dengan deret titik (waktu lokal, nilai), interpolasi linear. */
    fun setKeyframeSeries(clipId: String, key: String, points: List<Pair<Long, Float>>) {
        val def = Props.get(key) ?: return
        if (points.isEmpty()) return
        val frames = points.sortedBy { it.first }.map { Keyframe(it.first, it.second.coerceIn(def.min, def.max), Interp.LINEAR) }
        commit(mapClip(_project.value, clipId) { it.copy(keyframes = it.keyframes + (key to KeyframeTrack(frames))) })
    }

    /** Seperti [setKeyframeSeries] tetapi untuk beberapa properti sekaligus dalam satu langkah undo (hasil motion tracking). */
    fun setKeyframeSeriesMulti(clipId: String, series: Map<String, List<Pair<Long, Float>>>) {
        commit(
            mapClip(_project.value, clipId) { c ->
                var keyframes = c.keyframes
                for ((key, points) in series) {
                    val def = Props.get(key) ?: continue
                    if (points.isEmpty()) continue
                    val frames = points.sortedBy { it.first }
                        .map { Keyframe(it.first, it.second.coerceIn(def.min, def.max), Interp.LINEAR) }
                    keyframes = keyframes + (key to KeyframeTrack(frames))
                }
                c.copy(keyframes = keyframes)
            }
        )
    }

    /** Waktu lokal keyframe sebelumnya (dir<0) atau berikutnya (dir>0) dari [localMs]; null bila tidak ada. */
    fun neighborKeyframe(clipId: String, key: String, localMs: Long, dir: Int): Long? {
        val kt = _project.value.findClip(clipId)?.keyframes?.get(key) ?: return null
        return if (dir < 0) kt.frames.lastOrNull { it.timeMs < localMs - KEYFRAME_TOLERANCE_MS }?.timeMs
        else kt.frames.firstOrNull { it.timeMs > localMs + KEYFRAME_TOLERANCE_MS }?.timeMs
    }

    fun setTransition(clipId: String, isIn: Boolean, t: Transition) {
        commit(
            mapClip(_project.value, clipId) {
                if (isIn) it.copy(transitionIn = t) else it.copy(transitionOut = t)
            },
            coalesce = "trans:$clipId:$isIn"
        )
    }

    // ---------------------------------------------------------------- snapping

    /** Titik magnet: 0, playhead, awal & akhir semua klip lain. */
    fun snapPoints(excludeClipId: String?, playheadMs: Long): LongArray {
        val out = ArrayList<Long>()
        out.add(0L)
        out.add(playheadMs)
        for (t in _project.value.tracks) for (c in t.clips) {
            if (c.id == excludeClipId) continue
            out.add(c.startMs)
            out.add(c.endMs)
        }
        return out.toLongArray()
    }

    /** Magnet: sisi awal atau akhir klip menempel ke titik terdekat dalam [thresholdMs]. */
    fun snapStart(desiredStart: Long, durationMs: Long, excludeClipId: String?, playheadMs: Long, thresholdMs: Long): Long {
        val pts = snapPoints(excludeClipId, playheadMs)
        var best = desiredStart
        var bestDist = thresholdMs + 1
        for (pt in pts) {
            val d1 = abs(pt - desiredStart)
            if (d1 < bestDist) { bestDist = d1; best = pt }
            val d2 = abs(pt - (desiredStart + durationMs))
            if (d2 < bestDist) { bestDist = d2; best = pt - durationMs }
        }
        return if (bestDist <= thresholdMs) best else desiredStart
    }

    fun snapPoint(desired: Long, excludeClipId: String?, playheadMs: Long, thresholdMs: Long): Long {
        var best = desired
        var bestDist = thresholdMs + 1
        for (pt in snapPoints(excludeClipId, playheadMs)) {
            val d = abs(pt - desired)
            if (d < bestDist) { bestDist = d; best = pt }
        }
        return if (bestDist <= thresholdMs) best else desired
    }
}
