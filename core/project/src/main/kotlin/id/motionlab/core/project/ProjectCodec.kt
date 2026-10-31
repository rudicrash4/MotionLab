package id.motionlab.core.project

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Upgrades the raw JSON of a document from [from] to `from + 1`. Pure and side-effect free. */
class ProjectMigration(val from: Int, val transform: (JsonObject) -> JsonObject)

/**
 * Reads and writes the project JSON. Unknown fields are ignored (forward compatible within a schema version);
 * documents written by a NEWER schema than this build understands are rejected instead of being silently
 * truncated, so a later save can never destroy data.
 */
class ProjectCodec(
    private val migrations: List<ProjectMigration> = emptyList(),
    private val currentVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(document: ProjectDocument): String {
        val problems = document.config.validate()
        if (problems.isNotEmpty()) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Invalid project config: ${problems.joinToString("; ")}")
        }
        return json.encodeToString(ProjectDocument.serializer(), document.copy(schemaVersion = currentVersion))
    }

    fun decode(text: String): ProjectDocument {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw MlException(ErrorCode.CORRUPT_DATA, "Project file is not valid JSON: ${e.message}", e)
        }
        val version = try {
            root["schemaVersion"]?.jsonPrimitive?.int ?: 0
        } catch (e: Exception) {
            throw MlException(ErrorCode.CORRUPT_DATA, "schemaVersion is not an integer", e)
        }
        if (version > currentVersion) {
            throw MlException(
                ErrorCode.UNSUPPORTED,
                "Project was saved by a newer MotionLab (schema $version > $currentVersion). Update the app.",
            )
        }
        var migrated = root
        var v = version
        while (v < currentVersion) {
            val migration = migrations.firstOrNull { it.from == v }
                ?: throw MlException(ErrorCode.UNSUPPORTED, "No migration from project schema $v")
            migrated = migration.transform(migrated)
            v++
            migrated = JsonObject(migrated + ("schemaVersion" to JsonPrimitive(v)))
        }
        return try {
            json.decodeFromJsonElement(ProjectDocument.serializer(), migrated)
        } catch (e: SerializationException) {
            throw MlException(ErrorCode.CORRUPT_DATA, "Project data is malformed: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw MlException(ErrorCode.CORRUPT_DATA, "Project data is malformed: ${e.message}", e)
        }
    }
}
