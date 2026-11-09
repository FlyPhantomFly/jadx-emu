package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LayeredSourceTest {
    private val a = TestDex.assemble(cls("Lp/A;", staticMethod("v()I", 1, "    const/4 v0, 1\n    return v0")))
    private val b = TestDex.assemble(
        cls("Lp/A;", staticMethod("v()I", 1, "    const/4 v0, 2\n    return v0")),   // same class, different body
        cls("Lp/B;", staticMethod("w()I", 1, "    const/16 v0, 9\n    return v0")),
    )

    @Test
    fun firstLayerWinsForAClassInBoth() {
        val layered = LayeredSource(listOf(a, b))
        assertNotNull(layered.method("Lp/A;", "v()I"))
        assertEquals(1, TestDex.vm(a).invoke(layered.method("Lp/A;", "v()I")!!))  // the A-layer body, value 1
    }

    @Test
    fun classesFromEitherLayerAreVisible() {
        val layered = LayeredSource(listOf(a, b))
        assertNotNull(layered.method("Lp/A;", "v()I"))
        assertNotNull(layered.method("Lp/B;", "w()I"))
        assertNull(layered.method("Lp/C;", "x()I"))
    }

    @Test
    fun allMethodsDeduplicatesAClassPresentInTwoLayers() {
        val layered = LayeredSource(listOf(a, b))
        val aMethods = layered.allMethods().filter { it.declClass == "Lp/A;" && it.ref.shortId == "v()I" }
        assertEquals(1, aMethods.size, "A->v appears once, from the first layer")
        assertTrue(layered.allMethods().any { it.declClass == "Lp/B;" })
    }

    @Test
    fun thePlusOperatorPutsTheLeftSideInFront() {
        val combined = a + b
        assertEquals(1, TestDex.vm(a).invoke(combined.method("Lp/A;", "v()I")!!))   // left (a) wins
        assertNotNull(combined.method("Lp/B;", "w()I"))
    }

    @Test
    fun plusFlattensInsteadOfNesting() {
        val combined = (a + b) + a
        assertTrue(combined is LayeredSource)
        assertEquals(3, (combined as LayeredSource).layers.size)
    }
}
