package id.motionlab.engine.rendering

/**
 * Least-recently-used cache bounded by a byte budget rather than an entry count. Not thread-safe: for GPU
 * resources it is only touched from the render thread.
 *
 * [onEvict] is called for entries the cache discards (evicted for space, replaced, or cleared) so the owner can
 * free the underlying resource. Entries taken out with [remove] are handed back to the caller instead.
 */
class LruByteCache<K : Any, V : Any>(
    val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private val map = LinkedHashMap<K, V>(16, 0.75f, true) // access order

    var bytes: Long = 0
        private set

    val size: Int get() = map.size

    /** Returns the value and marks it most recently used. */
    fun get(key: K): V? = map[key]

    fun contains(key: K): Boolean = map.containsKey(key)

    /**
     * Stores [value]. Returns false (and stores nothing) when the value alone is larger than the whole budget;
     * the caller still owns it in that case.
     */
    fun put(key: K, value: V): Boolean {
        val s = sizeOf(value)
        require(s >= 0) { "size must be >= 0" }
        if (s > maxBytes) return false
        map.remove(key)?.let { old ->
            bytes -= sizeOf(old)
            onEvict(key, old)
        }
        while (bytes + s > maxBytes) evictOldest()
        map[key] = value
        bytes += s
        return true
    }

    fun remove(key: K): V? {
        val v = map.remove(key) ?: return null
        bytes -= sizeOf(v)
        return v
    }

    fun clear() {
        val entries = map.entries.toList()
        map.clear()
        bytes = 0
        for (e in entries) onEvict(e.key, e.value)
    }

    private fun evictOldest() {
        val it = map.entries.iterator()
        val oldest = it.next()
        val key = oldest.key
        val value = oldest.value
        it.remove()
        bytes -= sizeOf(value)
        onEvict(key, value)
    }
}
