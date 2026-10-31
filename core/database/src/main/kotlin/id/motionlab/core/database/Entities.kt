package id.motionlab.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Index row for the home screen. The project content itself lives in `<directoryPath>/project.json`. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val directoryPath: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "modified_at_ms") val modifiedAtMs: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "frame_rate_num") val frameRateNumerator: Int,
    @ColumnInfo(name = "frame_rate_den") val frameRateDenominator: Int,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
)

/** Metadata cached about an imported media file (so the media bin does not re-probe on every launch). */
@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("project_id")],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val uri: String,
    val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
)
