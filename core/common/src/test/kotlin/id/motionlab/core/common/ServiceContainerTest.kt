package id.motionlab.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ServiceContainerTest {
    private class A
    private class B(val a: A)
    private class Loop1(c: ServiceContainer) {
        val other = c.get<Loop2>()
    }

    private class Loop2(c: ServiceContainer) {
        val other = c.get<Loop1>()
    }

    @Test
    fun factoriesAreLazySingletons() {
        val c = ServiceContainer()
        var created = 0
        c.register { created++; A() }
        c.register { B(c.get()) }
        assertEquals(0, created)
        val b1 = c.get<B>()
        val b2 = c.get<B>()
        assertSame(b1, b2)
        assertSame(b1.a, c.get<A>())
        assertEquals(1, created)
    }

    @Test
    fun missingServiceThrowsNotFound() {
        try {
            ServiceContainer().get<A>()
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.NOT_FOUND, e.code)
        }
    }

    @Test
    fun duplicateRegistrationRejected() {
        val c = ServiceContainer()
        c.registerInstance(A())
        try {
            c.registerInstance(A())
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, e.code)
        }
    }

    @Test
    fun circularFactoriesAreDetected() {
        val c = ServiceContainer()
        c.register { Loop1(c) }
        c.register { Loop2(c) }
        try {
            c.get<Loop1>()
            fail()
        } catch (e: MlException) {
            assertEquals(ErrorCode.INITIALIZATION, e.code)
        }
    }
}
