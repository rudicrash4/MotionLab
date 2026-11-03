package id.motionlab.core.common

/**
 * Minimal constructor-injection container (lazy singletons). Chosen over Hilt/Dagger/Koin to avoid an
 * extra annotation-processing dependency in Phase 1; see INTEGRATION_REPORT.md, row 11.
 */
class ServiceContainer {
    private val lock = Any()
    private val factories = HashMap<Class<*>, () -> Any>()
    private val instances = HashMap<Class<*>, Any>()
    private val resolving = HashSet<Class<*>>()

    fun <T : Any> register(type: Class<T>, factory: () -> T) {
        synchronized(lock) {
            if (factories.containsKey(type) || instances.containsKey(type)) {
                throw MlException(ErrorCode.INVALID_ARGUMENT, "Service already registered: ${type.name}")
            }
            factories[type] = factory
        }
    }

    fun <T : Any> registerInstance(type: Class<T>, instance: T) {
        synchronized(lock) {
            if (factories.containsKey(type) || instances.containsKey(type)) {
                throw MlException(ErrorCode.INVALID_ARGUMENT, "Service already registered: ${type.name}")
            }
            instances[type] = instance
        }
    }

    fun <T : Any> has(type: Class<T>): Boolean = synchronized(lock) {
        instances.containsKey(type) || factories.containsKey(type)
    }

    fun <T : Any> getOrNull(type: Class<T>): T? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        val existing = instances[type] as T?
        if (existing != null) return existing
        val factory = factories[type] ?: return null
        if (!resolving.add(type)) {
            throw MlException(ErrorCode.INITIALIZATION, "Circular dependency while creating ${type.name}")
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val created = factory() as T
            instances[type] = created
            factories.remove(type)
            created
        } finally {
            resolving.remove(type)
        }
    }

    fun <T : Any> get(type: Class<T>): T =
        getOrNull(type) ?: throw MlException(ErrorCode.NOT_FOUND, "No service registered for ${type.name}")

    inline fun <reified T : Any> register(noinline factory: () -> T) = register(T::class.java, factory)
    inline fun <reified T : Any> registerInstance(instance: T) = registerInstance(T::class.java, instance)
    inline fun <reified T : Any> get(): T = get(T::class.java)
    inline fun <reified T : Any> getOrNull(): T? = getOrNull(T::class.java)
}
