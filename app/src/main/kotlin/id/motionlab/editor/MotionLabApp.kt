package id.motionlab.editor

import android.app.Application
import android.util.Log
import id.motionlab.core.common.LogLevel
import id.motionlab.core.common.MlLog
import id.motionlab.core.common.ModuleRegistry
import id.motionlab.core.common.ServiceContainer
import id.motionlab.core.database.MotionLabDatabase
import id.motionlab.core.resource.ResourceManager
import id.motionlab.core.task.TaskScheduler
import id.motionlab.feature.AppServices
import java.io.File

class MotionLabApp : Application() {
    lateinit var services: AppServices
        private set
    private lateinit var registry: ModuleRegistry

    override fun onCreate() {
        super.onCreate()
        MlLog.addSink { level, tag, message, throwable ->
            val priority = when (level) {
                LogLevel.VERBOSE -> Log.VERBOSE
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
            Log.println(priority, "MotionLab/$tag", if (throwable != null) "$message\n${Log.getStackTraceString(throwable)}" else message)
        }

        val container = ServiceContainer()
        container.register { TaskScheduler() }
        container.register { ResourceManager() }
        container.register { MotionLabDatabase.create(this) }

        registry = ModuleRegistry(container).apply {
            register(NativeModule())
            register(DatabaseModule(this@MotionLabApp))
            register(SchedulerModule())
        }
        val report = registry.initializeAll()
        if (!report.isFullySuccessful) MlLog.w("MotionLabApp", "startup finished with problems: failed=${report.failed.keys} skipped=${report.skipped}")

        val projectsDir = File(filesDir, "projects").apply { mkdirs() }
        services = AppServices(container, report, projectsDir)
    }

    override fun onTerminate() {
        // Only called on emulators, but keeps shutdown behaviour testable.
        registry.shutdownAll()
        super.onTerminate()
    }
}
