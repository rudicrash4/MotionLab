package id.motionlab.core.resource

import id.motionlab.core.common.ErrorCode
import id.motionlab.core.common.MlResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceManagerTest {
    private class Loaded : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() {
            closes.incrementAndGet()
        }
    }

    private fun ref(id: String, uri: String = "content://media/$id") =
        ResourceRef(ResourceId(id), uri, ResourceKind.VIDEO)

    @Test
    fun sharedValueIsLoadedOnceAndClosedWithLastLease() {
        val m = ResourceManager()
        m.register(ref("a"))
        var loads = 0
        val l1 = (m.acquire(ResourceId("a")) { loads++; Loaded() } as MlResult.Success).value
        val l2 = (m.acquire(ResourceId("a")) { loads++; Loaded() } as MlResult.Success).value
        assertSame(l1.value, l2.value)
        assertEquals(1, loads)
        assertEquals(2, m.activeLeaseCount(ResourceId("a")))

        l1.close()
        assertEquals(0, l1.value.closes.get())
        l1.close() // idempotent
        assertEquals(1, m.activeLeaseCount(ResourceId("a")))
        l2.close()
        assertEquals(1, l2.value.closes.get())
        assertEquals(0, m.activeLeaseCount(ResourceId("a")))
    }

    @Test
    fun reacquireAfterFullReleaseReloads() {
        val m = ResourceManager()
        m.register(ref("a"))
        var loads = 0
        (m.acquire(ResourceId("a")) { loads++; Loaded() } as MlResult.Success).value.close()
        (m.acquire(ResourceId("a")) { loads++; Loaded() } as MlResult.Success).value.close()
        assertEquals(2, loads)
    }

    @Test
    fun loaderFailureIsReportedAndLeavesNoLease() {
        val m = ResourceManager()
        m.register(ref("a"))
        val r = m.acquire<Loaded>(ResourceId("a")) { throw java.io.IOException("disk") } as MlResult.Failure
        assertEquals(ErrorCode.IO, r.error.code)
        assertEquals(0, m.activeLeaseCount(ResourceId("a")))
    }

    @Test
    fun unknownResourceIsNotFound() {
        val r = ResourceManager().acquire(ResourceId("x")) { Loaded() } as MlResult.Failure
        assertEquals(ErrorCode.NOT_FOUND, r.error.code)
    }

    @Test
    fun unregisterRefusedWhileLeased() {
        val m = ResourceManager()
        m.register(ref("a"))
        val lease = (m.acquire(ResourceId("a")) { Loaded() } as MlResult.Success).value
        assertTrue(m.unregister(ResourceId("a")) is MlResult.Failure)
        lease.close()
        assertTrue(m.unregister(ResourceId("a")) is MlResult.Success)
        assertNull(m.get(ResourceId("a")))
    }

    @Test
    fun missingFileFailsValidationAndExistingFilePasses() {
        val m = ResourceManager()
        val missing = m.register(ref("m", "/definitely/not/here.mp4")) as MlResult.Failure
        assertEquals(ErrorCode.NOT_FOUND, missing.error.code)

        val f = File.createTempFile("mlres", ".bin")
        try {
            assertTrue(m.register(ref("ok", f.absolutePath)) is MlResult.Success)
            assertTrue(m.register(ref("ok2", f.toURI().toString())) is MlResult.Success)
            f.delete()
            assertEquals(setOf(ResourceId("ok"), ResourceId("ok2")), m.validateAll().keys)
        } finally {
            f.delete()
        }
    }

    @Test
    fun conflictingReRegistrationRejected() {
        val m = ResourceManager()
        m.register(ref("a"))
        assertTrue(m.register(ref("a")) is MlResult.Success)
        assertTrue(m.register(ref("a", "content://other")) is MlResult.Failure)
    }

    @Test
    fun closeReleasesEverything() {
        val m = ResourceManager()
        m.register(ref("a"))
        val lease = (m.acquire(ResourceId("a")) { Loaded() } as MlResult.Success).value
        m.close()
        assertEquals(1, lease.value.closes.get())
        assertTrue(m.all().isEmpty())
    }
}
