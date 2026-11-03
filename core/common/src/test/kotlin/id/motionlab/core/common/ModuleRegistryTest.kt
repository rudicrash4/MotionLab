package id.motionlab.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModuleRegistryTest {
    private class FakeModule(
        override val name: String,
        override val dependsOn: Set<String> = emptySet(),
        private val failInit: Boolean = false,
        private val log: MutableList<String>,
    ) : MotionModule {
        override fun initialize(container: ServiceContainer) {
            if (failInit) throw IllegalStateException("boom")
            log += "init:$name"
        }

        override fun shutdown() {
            log += "stop:$name"
        }
    }

    @Test
    fun initializesInDependencyOrderAndShutsDownInReverse() {
        val log = ArrayList<String>()
        val registry = ModuleRegistry(ServiceContainer())
        registry.register(FakeModule("engine", setOf("core"), log = log))
        registry.register(FakeModule("core", log = log))
        registry.register(FakeModule("feature", setOf("engine"), log = log))

        val report = registry.initializeAll()
        assertTrue(report.isFullySuccessful)
        assertEquals(listOf("core", "engine", "feature"), report.initialized)

        registry.shutdownAll()
        assertEquals(
            listOf("init:core", "init:engine", "init:feature", "stop:feature", "stop:engine", "stop:core"),
            log,
        )
    }

    @Test
    fun failureSkipsDependentsButNotUnrelatedModules() {
        val log = ArrayList<String>()
        val registry = ModuleRegistry(ServiceContainer())
        registry.register(FakeModule("native", failInit = true, log = log))
        registry.register(FakeModule("media", setOf("native"), log = log))
        registry.register(FakeModule("database", log = log))

        val report = registry.initializeAll()
        assertEquals(setOf("native"), report.failed.keys)
        assertEquals(ErrorCode.INITIALIZATION, report.failed.getValue("native").code)
        assertTrue(report.skipped.getValue("media").contains("native"))
        assertEquals(listOf("database"), report.initialized)
        assertFalse(report.isFullySuccessful)
    }

    @Test
    fun missingDependencyIsSkipped() {
        val log = ArrayList<String>()
        val registry = ModuleRegistry(ServiceContainer())
        registry.register(FakeModule("a", setOf("ghost"), log = log))
        val report = registry.initializeAll()
        assertTrue(report.skipped.getValue("a").contains("ghost"))
    }

    @Test
    fun circularDependencyIsRejected() {
        val log = ArrayList<String>()
        val registry = ModuleRegistry(ServiceContainer())
        registry.register(FakeModule("a", setOf("b"), log = log))
        registry.register(FakeModule("b", setOf("a"), log = log))
        try {
            registry.initializeAll()
            fail("expected cycle detection")
        } catch (e: MlException) {
            assertEquals(ErrorCode.INITIALIZATION, e.code)
        }
    }
}
