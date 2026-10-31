package id.motionlab.core.project

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists a project as `<dir>/project.json`.
 *
 * Saving is crash-safe: the new content is written to `project.json.tmp`, flushed to disk, the previous
 * file is kept as `project.json.bak`, and only then is the temp file renamed over the real one. If the app
 * dies at any point, [load] finds either the old or the new complete file, or falls back to the backup.
 */
class ProjectStore(private val directory: File, private val codec: ProjectCodec = ProjectCodec()) {
    private val main = File(directory, FILE_NAME)
    private val tmp = File(directory, "$FILE_NAME.tmp")
    private val backup = File(directory, "$FILE_NAME.bak")

    fun exists(): Boolean = main.exists() || backup.exists()

    fun save(document: ProjectDocument) {
        val text = codec.encode(document) // validate before touching any file
        if (!directory.exists() && !directory.mkdirs()) {
            throw MlException(ErrorCode.IO, "Cannot create project directory ${directory.path}")
        }
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (main.exists()) {
                Files.copy(main.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp.toPath(), main.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw MlException(ErrorCode.IO, "Failed to save project: ${e.message}", e)
        }
    }

    /** Loads the project, recovering from the backup if the main file is missing or corrupt. */
    fun load(): ProjectDocument {
        if (main.exists()) {
            try {
                return codec.decode(main.readText(Charsets.UTF_8))
            } catch (e: MlException) {
                if (e.code != ErrorCode.CORRUPT_DATA || !backup.exists()) throw e
                MlLog.w("ProjectStore", "project.json is corrupt, recovering from backup", e)
            } catch (e: IOException) {
                if (!backup.exists()) throw MlException(ErrorCode.IO, "Cannot read project: ${e.message}", e)
            }
        }
        if (!backup.exists()) throw MlException(ErrorCode.NOT_FOUND, "No project found in ${directory.path}")
        return codec.decode(backup.readText(Charsets.UTF_8))
    }

    companion object {
        const val FILE_NAME = "project.json"
    }
}
