package id.motionlab.core.resource

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlException
import id.motionlab.core.common.MlLog
import id.motionlab.core.common.MlResult
import java.io.File
import java.net.URI

@JvmInline
value class ResourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "ResourceId must not be blank" }
    }

    override fun toString(): String = value
}

enum class ResourceKind { VIDEO, AUDIO, IMAGE, FONT, OTHER }

/** A project-level reference to external media. Contains no loaded data, so it is cheap and serializable. */
data class ResourceRef(
    val id: ResourceId,
    val uri: String,
    val kind: ResourceKind,
    val displayName: String? = null,
)

/** Returns null when valid, or the reason it is invalid. */
fun interface ResourceValidator {
    fun validate(ref: ResourceRef): MlException?
}

/** Rejects blank URIs and, for plain paths / file: URIs, files that do not exist. */
object DefaultResourceValidator : ResourceValidator {
    override fun validate(ref: ResourceRef): MlException? {
        if (ref.uri.isBlank()) return MlException(ErrorCode.INVALID_ARGUMENT, "Resource ${ref.id} has an empty uri")
        val path = localPathOrNull(ref.uri) ?: return null // content:// and other schemes are validated at open time
        val file = File(path)
        if (!file.exists()) return MlException(ErrorCode.NOT_FOUND, "File not found: $path")
        if (!file.isFile) return MlException(ErrorCode.INVALID_ARGUMENT, "Not a regular file: $path")
        if (!file.canRead()) return MlException(ErrorCode.IO, "File is not readable: $path")
        return null
    }

    internal fun localPathOrNull(uri: String): String? {
        if (!uri.contains("://") && !uri.startsWith("file:")) return uri
        return try {
            val parsed = URI(uri)
            if (parsed.scheme == "file") parsed.path else null
        } catch (_: Exception) {
            null
        }
    }
}

/** A counted hold on a loaded resource. Close it exactly when you stop using the value (extra closes are ignored). */
class ResourceLease<T : Any> internal constructor(
    val ref: ResourceRef,
    val value: T,
    private val onClose: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        onClose()
    }
}

/**
 * Tracks resource references and ref-counts loaded instances. The loaded value is created on the first
 * [acquire], shared by later leases, and closed (if it is [AutoCloseable]) when the last lease is released.
 */
class ResourceManager(private val validator: ResourceValidator = DefaultResourceValidator) : AutoCloseable {
    private class Entry(val ref: ResourceRef) {
        var value: Any? = null
        var leases = 0
    }

    private val lock = Any()
    private val entries = LinkedHashMap<ResourceId, Entry>()

    fun register(ref: ResourceRef): MlResult<ResourceRef> {
        validator.validate(ref)?.let { return MlResult.Failure(it) }
        synchronized(lock) {
            val existing = entries[ref.id]
            if (existing != null) {
                if (existing.ref == ref) return MlResult.Success(ref)
                return MlResult.Failure(
                    MlException(ErrorCode.INVALID_ARGUMENT, "Resource id ${ref.id} already registered with different data"),
                )
            }
            entries[ref.id] = Entry(ref)
        }
        return MlResult.Success(ref)
    }

    fun get(id: ResourceId): ResourceRef? = synchronized(lock) { entries[id]?.ref }

    fun all(): List<ResourceRef> = synchronized(lock) { entries.values.map { it.ref } }

    fun activeLeaseCount(id: ResourceId): Int = synchronized(lock) { entries[id]?.leases ?: 0 }

    /** Re-runs validation for every registered reference; returns the invalid ones (e.g. a file deleted since import). */
    fun validateAll(): Map<ResourceId, MlException> {
        val snapshot = all()
        val problems = LinkedHashMap<ResourceId, MlException>()
        for (ref in snapshot) validator.validate(ref)?.let { problems[ref.id] = it }
        return problems
    }

    fun unregister(id: ResourceId): MlResult<Unit> = synchronized(lock) {
        val entry = entries[id] ?: return MlResult.Failure(MlException(ErrorCode.NOT_FOUND, "Unknown resource $id"))
        if (entry.leases > 0) {
            return MlResult.Failure(
                MlException(ErrorCode.INVALID_ARGUMENT, "Resource $id still has ${entry.leases} active lease(s)"),
            )
        }
        entries.remove(id)
        MlResult.Success(Unit)
    }

    fun <T : Any> acquire(id: ResourceId, loader: (ResourceRef) -> T): MlResult<ResourceLease<T>> {
        val entry: Entry
        val value: T
        synchronized(lock) {
            entry = entries[id] ?: return MlResult.Failure(MlException(ErrorCode.NOT_FOUND, "Unknown resource $id"))
            val existing = entry.value
            if (existing == null) {
                val loaded = try {
                    loader(entry.ref)
                } catch (e: MlException) {
                    return MlResult.Failure(e)
                } catch (e: Exception) {
                    return MlResult.Failure(MlException(ErrorCode.IO, "Failed to load $id: ${e.message}", e))
                }
                entry.value = loaded
                value = loaded
            } else {
                @Suppress("UNCHECKED_CAST")
                value = existing as? T
                    ?: return MlResult.Failure(
                        MlException(ErrorCode.INVALID_ARGUMENT, "Resource $id is already loaded as ${existing.javaClass.name}"),
                    )
            }
            entry.leases++
        }
        return MlResult.Success(ResourceLease(entry.ref, value) { release(entry) })
    }

    private fun release(entry: Entry) {
        var toClose: Any? = null
        synchronized(lock) {
            entry.leases--
            if (entry.leases == 0) {
                toClose = entry.value
                entry.value = null
            }
        }
        closeQuietly(entry.ref, toClose)
    }

    /** Closes every loaded value regardless of outstanding leases (app shutdown / project close). */
    override fun close() {
        val toClose = ArrayList<Pair<ResourceRef, Any>>()
        synchronized(lock) {
            for (e in entries.values) {
                e.value?.let { toClose += e.ref to it }
                e.value = null
                e.leases = 0
            }
            entries.clear()
        }
        for ((ref, v) in toClose) closeQuietly(ref, v)
    }

    private fun closeQuietly(ref: ResourceRef, value: Any?) {
        if (value is AutoCloseable) {
            try {
                value.close()
            } catch (e: Exception) {
                MlLog.w("ResourceManager", "closing ${ref.id} failed", e)
            }
        }
    }
}
