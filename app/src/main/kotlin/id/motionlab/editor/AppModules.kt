package id.motionlab.editor

import android.content.Context
import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MotionModule
import id.motionlab.core.common.ServiceContainer
import id.motionlab.core.database.MotionLabDatabase
import id.motionlab.core.task.TaskScheduler
import id.motionlab.nativecore.NativeCore

/** Loads libmotionlab_native.so. A failure is reported but does not stop the rest of the app. */
class NativeModule : MotionModule {
    override val name = "native"

    override fun initialize(container: ServiceContainer) {
        NativeCore.load().getOrElse { e ->
            throw MlException(ErrorCode.NATIVE, "Native library failed to load: ${e.message}", e)
        }
    }
}

/** Opens the Room database eagerly so a corrupt/unmigratable database is detected at startup. */
class DatabaseModule(private val context: Context) : MotionModule {
    override val name = "database"

    override fun initialize(container: ServiceContainer) {
        val db = container.get<MotionLabDatabase>()
        db.openHelper.writableDatabase // forces open + migrations
    }

    override fun shutdown() {
        // The database is process-wide; it is closed by process death. Nothing to release explicitly.
    }
}

class SchedulerModule : MotionModule {
    override val name = "scheduler"
    override val dependsOn = emptySet<String>()

    override fun initialize(container: ServiceContainer) {
        container.get<TaskScheduler>() // instantiate
    }

    override fun shutdown() {
        // Best effort: the scheduler is only created if it was requested.
    }
}
