package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.policy.SignatureSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Executable

class HostBoundaryTest {
    private fun m(cls: Class<*>, name: String, vararg params: Class<*>): Executable = cls.getMethod(name, *params)
    private fun ctor(cls: Class<*>, vararg params: Class<*>): Executable = cls.getConstructor(*params)

    @Test
    fun defaultsAllowPureJavaAndDenyTimeRandomUnsafe() {
        val h = HostBoundary()
        assertTrue(h.canExecute(m(String::class.java, "length")))
        assertTrue(h.canExecute(m(Math::class.java, "abs", Int::class.javaPrimitiveType!!)))
        assertEquals(HostBoundary.DENIED, h.denial(m(System::class.java, "currentTimeMillis")))
        assertEquals(HostBoundary.DENIED, h.denial(ctor(java.util.Random::class.java)))
        assertEquals(HostBoundary.DENIED, h.denial(m(Runtime::class.java, "exec", String::class.java)))
        assertEquals(HostBoundary.DENIED, h.denial(m(System::class.java, "exit", Int::class.javaPrimitiveType!!)))
    }

    @Test
    fun namespaceIsLimitedToJavaAndJavaxCrypto() {
        val h = HostBoundary()
        assertTrue(h.canHandle(String::class.java))
        assertTrue(h.canHandle(javax.crypto.Cipher::class.java))
        assertFalse(h.canHandle(javax.swing.JFrame::class.java))
        assertFalse(h.canHandle(org.junit.jupiter.api.Assertions::class.java))
        assertEquals(HostBoundary.OUTSIDE_NAMESPACE, h.denial(m(org.junit.jupiter.api.Assertions::class.java, "fail")))
        assertEquals(HostBoundary.DENIED, h.denial(m(javax.swing.JFrame::class.java, "getTitle")))
    }

    @Test
    fun restrictTogglesReallowBundledSets() {
        val time = HostBoundary(restrictTime = false)
        assertNull(time.denial(m(System::class.java, "currentTimeMillis")))
        assertEquals(HostBoundary.DENIED, time.denial(ctor(java.util.Random::class.java)))
        val random = HostBoundary(restrictRandom = false)
        assertNull(random.denial(ctor(java.util.Random::class.java)))
        assertEquals(HostBoundary.DENIED, random.denial(m(System::class.java, "currentTimeMillis")))
    }

    @Test
    fun userDenyAndAllowOverride() {
        val deny = SignatureSet.parse("java.lang.String#toUpperCase()")
        val h = HostBoundary(deny = deny)
        assertEquals(HostBoundary.DENIED, h.denial(m(String::class.java, "toUpperCase")))
        assertNull(h.denial(m(String::class.java, "toLowerCase")))

        val allow = SignatureSet.parse("java.lang.System#currentTimeMillis()")
        val h2 = HostBoundary(allow = allow)
        assertNull(h2.denial(m(System::class.java, "currentTimeMillis")))
        assertEquals(HostBoundary.DENIED, h2.denial(m(System::class.java, "nanoTime")))
        assertEquals(listOf("java.lang.System#currentTimeMillis()"), h2.reallowed())
    }

    @Test
    fun anyOverloadAndClassWideEntries() {
        val h = HostBoundary(deny = SignatureSet.parse("java.lang.String#valueOf(**)\njava.util.ArrayList"))
        assertEquals(HostBoundary.DENIED, h.denial(m(String::class.java, "valueOf", Int::class.javaPrimitiveType!!)))
        assertEquals(HostBoundary.DENIED, h.denial(m(String::class.java, "valueOf", Char::class.javaPrimitiveType!!)))
        assertNull(h.denial(m(String::class.java, "length")))
        assertEquals(HostBoundary.DENIED, h.denial(ctor(java.util.ArrayList::class.java)))
        assertEquals(HostBoundary.DENIED, h.denial(m(java.util.ArrayList::class.java, "size")))
        assertFalse(h.canHandle(java.util.ArrayList::class.java))
    }

    @Test
    fun denyMatchesThroughReceiverHierarchy() {
        val h = HostBoundary(deny = SignatureSet.parse("java.util.AbstractList#get(int)"))
        val get = m(java.util.ArrayList::class.java, "get", Int::class.javaPrimitiveType!!)
        assertEquals(HostBoundary.DENIED, h.denial(get, java.util.ArrayList::class.java))
        val h2 = HostBoundary(deny = SignatureSet.parse("java.util.List#get(int)"))
        assertEquals(HostBoundary.DENIED, h2.denial(get, java.util.ArrayList::class.java))
    }

    @Test
    fun disabledDeniesEverything() {
        val h = HostBoundary.disabled()
        assertEquals(HostBoundary.DISABLED, h.denial(m(String::class.java, "length")))
        assertFalse(h.canHandle(String::class.java))
    }

    @Test
    fun parseRejectsUnknownDirective() {
        try {
            SignatureSet.parse("@bogus x")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown directive"))
        }
    }
}
