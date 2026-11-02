package jadx.plugins.emu.host

import jadx.plugins.emu.api.NativeBackend
import jadx.plugins.emu.api.NativeRequest
import jadx.plugins.emu.exec.NativeBridge
import jadx.plugins.emu.exec.NotHandled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeBackendRegistryTest {
    private class Fake(override val id: String) : NativeBackend {
        override fun open(request: NativeRequest): NativeBridge = object : NativeBridge {
            override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? = NotHandled
        }
    }

    @Test
    fun aRegisteredBackendCanBeLookedUpById() {
        val r = NativeBackendRegistry()
        val b = Fake("unidbg")
        assertTrue(r.register("native-unidbg", b))
        assertSame(b, r.get("unidbg"))
    }

    @Test
    fun anUnknownIdIsNullNotAnError() {
        assertNull(NativeBackendRegistry().get("nope"))
    }

    @Test
    fun theFirstRegistrationOfAnIdIsKept() {
        val r = NativeBackendRegistry()
        val first = Fake("x")
        assertTrue(r.register("a", first))
        assertFalse(r.register("b", Fake("x")))
        assertSame(first, r.get("x"))
    }

    @Test
    fun aBlankIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NativeBackendRegistry().register("a", Fake("  ")) }
    }

    @Test
    fun idsComeBackInRegistrationOrder() {
        val r = NativeBackendRegistry()
        r.register("a", Fake("zeta")); r.register("a", Fake("alpha")); r.register("b", Fake("mid"))
        assertEquals(listOf("zeta", "alpha", "mid"), r.ids())
    }

    @Test
    fun unloadingAnExtensionDropsOnlyItsOwnBackends() {
        val r = NativeBackendRegistry()
        r.register("one", Fake("a")); r.register("two", Fake("b")); r.register("one", Fake("c"))
        assertEquals(listOf("a", "c"), r.removeOwner("one"))
        assertEquals(listOf("b"), r.ids())
        assertNull(r.get("a"))
    }

    @Test
    fun anIdFreedByUnloadCanBeTakenAgain() {
        val r = NativeBackendRegistry()
        r.register("one", Fake("a"))
        r.removeOwner("one")
        assertTrue(r.register("two", Fake("a")))
    }

    @Test
    fun clearEmptiesEverything() {
        val r = NativeBackendRegistry()
        r.register("one", Fake("a")); r.clear()
        assertTrue(r.ids().isEmpty())
    }
}
