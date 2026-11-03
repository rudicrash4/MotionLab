package id.motionlab.core.common

/** A unit of startup/shutdown work contributed by a feature or engine module. */
interface MotionModule {
    val name: String
    val dependsOn: Set<String> get() = emptySet()
    fun initialize(container: ServiceContainer)
    fun shutdown() {}
}

class ModuleInitReport(
    /** Modules that initialized successfully, in initialization order. */
    val initialized: List<String>,
    val failed: Map<String, MlException>,
    /** Module name -> reason it was not started (missing/failed dependency). */
    val skipped: Map<String, String>,
) {
    val isFullySuccessful: Boolean get() = failed.isEmpty() && skipped.isEmpty()
}

/**
 * Initializes modules in dependency order. A failing module never prevents unrelated modules from
 * starting (graceful degradation); its dependents are skipped with a reason.
 */
class ModuleRegistry(private val container: ServiceContainer) {
    private val modules = LinkedHashMap<String, MotionModule>()
    private var started: List<MotionModule> = emptyList()

    fun register(module: MotionModule) {
        if (modules.containsKey(module.name)) {
            throw MlException(ErrorCode.INVALID_ARGUMENT, "Duplicate module: ${module.name}")
        }
        modules[module.name] = module
    }

    fun initializeAll(): ModuleInitReport {
        val order = topologicalOrder()
        val initialized = ArrayList<String>()
        val startedModules = ArrayList<MotionModule>()
        val failed = LinkedHashMap<String, MlException>()
        val skipped = LinkedHashMap<String, String>()

        for (module in order) {
            val missing = module.dependsOn.filter { !modules.containsKey(it) }
            val notStarted = module.dependsOn.filter { modules.containsKey(it) && it !in initialized }
            when {
                missing.isNotEmpty() -> skipped[module.name] = "missing dependency: ${missing.joinToString()}"
                notStarted.isNotEmpty() -> skipped[module.name] = "dependency not started: ${notStarted.joinToString()}"
                else -> try {
                    module.initialize(container)
                    initialized += module.name
                    startedModules += module
                    MlLog.i("ModuleRegistry", "initialized ${module.name}")
                } catch (e: Exception) {
                    val error = e as? MlException
                        ?: MlException(ErrorCode.INITIALIZATION, "${module.name} failed: ${e.message}", e)
                    failed[module.name] = error
                    MlLog.e("ModuleRegistry", "failed to initialize ${module.name}", e)
                }
            }
        }
        started = startedModules
        return ModuleInitReport(initialized, failed, skipped)
    }

    fun shutdownAll() {
        for (module in started.asReversed()) {
            try {
                module.shutdown()
            } catch (e: Exception) {
                MlLog.e("ModuleRegistry", "shutdown of ${module.name} failed", e)
            }
        }
        started = emptyList()
    }

    private fun topologicalOrder(): List<MotionModule> {
        val result = ArrayList<MotionModule>()
        val state = HashMap<String, Int>() // 1 = visiting, 2 = done

        fun visit(module: MotionModule) {
            when (state[module.name]) {
                2 -> return
                1 -> throw MlException(ErrorCode.INITIALIZATION, "Circular module dependency at ${module.name}")
            }
            state[module.name] = 1
            for (dep in module.dependsOn) modules[dep]?.let(::visit)
            state[module.name] = 2
            result += module
        }
        modules.values.forEach(::visit)
        return result
    }
}
