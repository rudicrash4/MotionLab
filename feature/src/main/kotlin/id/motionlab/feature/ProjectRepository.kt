package id.motionlab.feature

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.database.ProjectDao
import id.motionlab.core.database.ProjectEntity
import id.motionlab.core.project.ProjectConfig
import id.motionlab.core.project.ProjectDocument
import id.motionlab.core.project.ProjectStore
import java.io.File
import java.util.UUID

/**
 * Creates, opens and deletes projects. The project content lives in `<projectsDir>/<id>/project.json`
 * (crash-safe, see ProjectStore); Room only holds the index used by the home screen. Blocking: call from IO.
 */
class ProjectRepository(private val dao: ProjectDao, private val projectsDir: File) {
    suspend fun create(name: String, config: ProjectConfig = ProjectConfig(), now: Long = System.currentTimeMillis()): ProjectDocument {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw MlException(ErrorCode.INVALID_ARGUMENT, "Project name must not be empty")
        val id = UUID.randomUUID().toString()
        val document = ProjectDocument(id = id, name = trimmed, createdAtMs = now, modifiedAtMs = now, config = config)
        val dir = File(projectsDir, id)
        ProjectStore(dir).save(document) // file first: an index row must never point at a missing project
        dao.upsert(entityOf(document, dir))
        return document
    }

    fun open(id: String): ProjectDocument = ProjectStore(File(projectsDir, id)).load()

    suspend fun save(document: ProjectDocument, now: Long = System.currentTimeMillis()): ProjectDocument {
        val updated = document.copy(modifiedAtMs = now)
        val dir = File(projectsDir, updated.id)
        ProjectStore(dir).save(updated)
        dao.upsert(entityOf(updated, dir))
        return updated
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        File(projectsDir, id).deleteRecursively()
    }

    private fun entityOf(d: ProjectDocument, dir: File) = ProjectEntity(
        id = d.id,
        name = d.name,
        directoryPath = dir.absolutePath,
        createdAtMs = d.createdAtMs,
        modifiedAtMs = d.modifiedAtMs,
        width = d.config.width,
        height = d.config.height,
        frameRateNumerator = d.config.frameRateNumerator,
        frameRateDenominator = d.config.frameRateDenominator,
        durationUs = d.config.durationUs,
    )
}
