package id.motionlab.feature

import id.motionlab.core.common.ModuleInitReport
import id.motionlab.core.common.ServiceContainer
import id.motionlab.core.database.MotionLabDatabase
import java.io.File

/** What the feature screens need from the application, resolved from the [ServiceContainer] in one place. */
class AppServices(
    val container: ServiceContainer,
    val initReport: ModuleInitReport,
    val projectsDir: File,
) {
    val database: MotionLabDatabase get() = container.get()
}
