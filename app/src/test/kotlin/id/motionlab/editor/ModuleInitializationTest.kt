package id.motionlab.editor

import androidx.test.core.app.ApplicationProvider
import id.motionlab.core.common.ModuleRegistry
import id.motionlab.core.common.ServiceContainer
import id.motionlab.core.database.MotionLabDatabase
import id.motionlab.core.resource.ResourceManager
import id.motionlab.core.task.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Module initialization test. On the JVM the native .so is not available, so the native module is EXPECTED
 * to fail while every other module still initializes; this verifies graceful degradation. The real native
 * load is verified by the instrumented test in :native:cpp-core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModuleInitializationTest {
    @Test
    fun nativeFailureDoesNotBlockOtherModules() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = ServiceContainer()
        container.register { TaskScheduler(threads = 1) }
        container.register { ResourceManager() }
        container.register { MotionLabDatabase.create(context) }

        val registry = ModuleRegistry(container).apply {
            register(NativeModule())
            register(DatabaseModule(context))
            register(SchedulerModule())
        }
        val report = registry.initializeAll()
        try {
            assertEquals(setOf("native"), report.failed.keys)
            assertTrue(report.initialized.containsAll(listOf("database", "scheduler")))
        } finally {
            registry.shutdownAll()
            container.get<TaskScheduler>().shutdown()
            container.get<MotionLabDatabase>().close()
        }
    }
}
