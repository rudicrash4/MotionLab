package id.motionlab.core.resource

/**
 * Small thread-safe LRU cache bounded by a total "cost" (bytes for bitmaps, entries for small things), so thumbnail and
 * waveform caches cannot grow without limit. [onEvict] lets the owner recycle or close the value.
 */
class BoundedCache<K : Any, V : Any>(
    private val maxCost: Long,
    private val costOf: (V) -> Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var cost = 0L

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        val c = costOf(value)
        map.put(key, value)?.let { old -> cost -= costOf(old); onEvict(key, old) }
        cost += c
        val it = map.entries.iterator()
        while (cost > maxCost && it.hasNext()) {
            val e = it.next()
            if (e.key == key && map.size == 1) break // never evict the only (just inserted) entry
            cost -= costOf(e.value)
            it.remove()
            onEvict(e.key, e.value)
        }
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)?.let { cost -= costOf(it); onEvict(key, it) }
    }

    @Synchronized
    fun removeWhere(predicate: (K) -> Boolean) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key)) { cost -= costOf(e.value); it.remove(); onEvict(e.key, e.value) }
        }
    }

    @Synchronized
    fun clear() {
        for ((k, v) in map) onEvict(k, v)
        map.clear()
        cost = 0
    }

    @get:Synchronized
    val size: Int get() = map.size

    @get:Synchronized
    val totalCost: Long get() = cost
}
