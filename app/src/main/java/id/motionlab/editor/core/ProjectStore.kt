package id.motionlab.editor.core

import android.content.Context
import java.io.File

data class ProjectInfo(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val durationMs: Long,
    val clipCount: Int,
    val aspectW: Int = 16,
    val aspectH: Int = 9
)

/** Hasil pemuatan proyek: [recovered] true bila file utama rusak dan cadangan (.bak) dipakai. */
data class LoadResult(val project: Project, val recovered: Boolean)

/**
 * Penyimpanan banyak proyek di `filesDir/projects/<id>.json`.
 *  - Tulis atomik: tulis ke .tmp, simpan versi lama sebagai .bak, lalu rename.
 *  - File rusak TIDAK dihapus: disalin ke .corrupt sebelum memakai cadangan.
 *  - Proyek lama (`project_v3.json`) diimpor sekali tanpa menghapus file aslinya.
 *  - Tidak ada kode yang menghapus proyek otomatis; hapus hanya lewat [delete] atas konfirmasi pengguna.
 */
class ProjectStore(context: Context) {
    private val appCtx = context.applicationContext
    private val dir = File(appCtx.filesDir, "projects").apply { mkdirs() }
    private val prefs = appCtx.getSharedPreferences("motionlab", Context.MODE_PRIVATE)
    private val legacy = File(appCtx.filesDir, "project_v3.json")

    private fun fileOf(id: String) = File(dir, safe(id) + ".json")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    @Synchronized
    fun save(p: Project): Boolean {
        return try {
            val main = fileOf(p.id)
            val tmp = File(dir, safe(p.id) + ".json.tmp")
            val bak = File(dir, safe(p.id) + ".json.bak")
            tmp.writeText(ProjectSerializer.toJson(p))
            if (main.exists()) {
                try { main.copyTo(bak, overwrite = true) } catch (_: Exception) { }
            }
            if (!tmp.renameTo(main)) {
                // rename gagal: salin isi (main tetap ada dan bak sudah menyimpan versi sebelumnya)
                tmp.copyTo(main, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun load(id: String): LoadResult? {
        val main = fileOf(id)
        val bak = File(dir, safe(id) + ".json.bak")
        if (main.exists()) {
            try {
                return LoadResult(ProjectSerializer.fromJson(main.readText()), false)
            } catch (_: Exception) {
                try { main.copyTo(File(dir, safe(id) + ".json.corrupt"), overwrite = true) } catch (_: Exception) { }
            }
        }
        if (bak.exists()) {
            try {
                return LoadResult(ProjectSerializer.fromJson(bak.readText()), true)
            } catch (_: Exception) { }
        }
        return null
    }

    @Synchronized
    fun list(): List<ProjectInfo> {
        importLegacyIfNeeded()
        val out = ArrayList<ProjectInfo>()
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        for (f in files) {
            try {
                val p = ProjectSerializer.fromJson(f.readText())
                out.add(ProjectInfo(p.id, p.name, p.updatedAt, p.durationMs, p.tracks.sumOf { it.clips.size }, p.aspectW, p.aspectH))
            } catch (_: Exception) {
                // Coba cadangan agar proyek tetap muncul di daftar.
                val id = f.name.removeSuffix(".json")
                val b = File(dir, f.name + ".bak")
                try {
                    val p = ProjectSerializer.fromJson(b.readText())
                    out.add(ProjectInfo(p.id, p.name + " (pulihkan)", p.updatedAt, p.durationMs, p.tracks.sumOf { it.clips.size }, p.aspectW, p.aspectH))
                } catch (_: Exception) {
                    out.add(ProjectInfo(id, "Proyek rusak ($id)", f.lastModified(), 0L, 0, 16, 9))
                }
            }
        }
        return out.sortedByDescending { it.updatedAt }
    }

    private fun importLegacyIfNeeded() {
        if (prefs.getBoolean("legacy_imported", false)) return
        try {
            if (legacy.exists()) {
                val p = ProjectSerializer.fromJson(legacy.readText())
                if (!fileOf(p.id).exists()) save(p)
                prefs.edit().putString("last_id", p.id).apply()
            }
        } catch (_: Exception) {
            // File lama dibiarkan apa adanya.
        }
        prefs.edit().putBoolean("legacy_imported", true).apply()
    }

    fun exists(id: String): Boolean = fileOf(id).exists()

    var lastOpenedId: String?
        get() = prefs.getString("last_id", null)
        set(v) { prefs.edit().putString("last_id", v).apply() }

    /** Proyek yang dibuka saat aplikasi mulai: terakhir dibuka, atau yang terbaru, atau null. */
    @Synchronized
    fun loadInitial(): LoadResult? {
        val infos = list()
        val last = lastOpenedId
        val order = ArrayList<String>()
        if (last != null) order.add(last)
        for (i in infos) if (i.id != last) order.add(i.id)
        for (id in order) {
            val r = load(id)
            if (r != null) return r
        }
        return null
    }

    @Synchronized
    fun duplicate(id: String): Project? {
        val src = load(id)?.project ?: return null
        val now = System.currentTimeMillis()
        val copy = src.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = src.name + " (salinan)",
            createdAt = now, updatedAt = now
        )
        return if (save(copy)) copy else null
    }

    /** Menghapus file proyek. Hanya dipanggil setelah konfirmasi eksplisit pengguna. */
    @Synchronized
    fun delete(id: String) {
        fileOf(id).delete()
        File(dir, safe(id) + ".json.bak").delete()
        File(dir, safe(id) + ".json.tmp").delete()
        if (lastOpenedId == id) lastOpenedId = null
    }
}
