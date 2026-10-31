package id.motionlab.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ProjectEntity::class, AssetEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MotionLabDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun assetDao(): AssetDao

    companion object {
        const val FILE_NAME = "motionlab.db"

        fun create(context: Context): MotionLabDatabase =
            Room.databaseBuilder(context.applicationContext, MotionLabDatabase::class.java, FILE_NAME)
                .addMigrations(*MotionLabMigrations.ALL)
                // No fallbackToDestructiveMigration(): project metadata must never be dropped silently.
                .build()
    }
}

/**
 * Schema 1 is the first version, so there are no migrations yet. When the schema changes, bump the
 * `version` above, add the Migration here, and keep the exported schema JSON under `schemas/`.
 */
object MotionLabMigrations {
    val ALL: Array<Migration> = emptyArray()
}
