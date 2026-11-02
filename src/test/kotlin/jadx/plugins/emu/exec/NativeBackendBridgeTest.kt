package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.api.NativeBackend
import jadx.plugins.emu.api.NativeRequest
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.plugins.emu.host.NativeBackendRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** A native method in an emulated app, run through a bridge that came out of a registered backend. */
class NativeBackendBridgeTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, staticMethod("callNat(I)I", 2, """
            invoke-static {p0}, LT;->nat(I)I
            move-result v0
            return v0
        """) + staticMethod("callOther(I)I", 2, """
            invoke-static {p0}, LT;->other(I)I
            move-result v0
            return v0
        """) + ".method public static native nat(I)I\n.end method\n" + ".method public static native other(I)I\n.end method\n"))

    private class Recording : NativeBackend {
        override val id = "fake"
        val opened = ArrayList<NativeRequest>()
        val ran = ArrayList<List<Any?>>()
        override fun open(request: NativeRequest): NativeBridge {
            opened += request
            return object : NativeBridge {
                override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? {
                    if ("L$className;->$methodName" !in request.methods) return NotHandled   // the allow-list rule
                    ran += listOf(className, methodName, signature, args, receiver)
                    return (args[0] as Int) * 3
                }
            }
        }
    }

    private fun bridgeFrom(backend: Recording): NativeBridge {
        val registry = NativeBackendRegistry()
        registry.register("test", backend)
        return registry.get("fake")!!.open(NativeRequest(File("app.apk"), setOf("LT;->nat"), listOf("libt.so")))
    }

    @Test
    fun anAllowedNativeRunsThroughTheBackendAndItsResultFlowsBack() {
        val backend = Recording()
        val vm = TestDex.context(source).newVm(nativeBridge = bridgeFrom(backend), androidEnvUnknown = false)
        assertEquals(21, TestDex.call(vm, source, c, "callNat(I)I", 7))
        assertEquals(1, backend.opened.size)
    }

    @Test
    fun theEnginePassesTheInternalClassNameNotADescriptor() {
        val backend = Recording()
        val vm = TestDex.context(source).newVm(nativeBridge = bridgeFrom(backend), androidEnvUnknown = false)
        TestDex.call(vm, source, c, "callNat(I)I", 7)
        // class LT; arrives as "T": no leading L, no semicolon. A backend builds "L$className;" itself.
        assertEquals(listOf<Any?>("T", "nat", "(I)I", listOf<Any?>(7), null), backend.ran.single())
    }

    private fun runOther(bridge: NativeBridge?): Pair<Any?, List<String>> {
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(source).newVm(nativeBridge = bridge, androidEnvUnknown = false, diagnostics = listOf(DiagnosticSink { diags += it }))
        return TestDex.call(vm, source, c, "callOther(I)I", 7) to diags.map { "${it.kind}:${it.detail}" }
    }

    @Test
    fun aNativeOutsideTheAllowListStaysUnknownAndNeverReachesTheBackend() {
        val backend = Recording()
        val (result, diags) = runOther(bridgeFrom(backend))
        assertTrue(result is UnknownVal, "expected UnknownVal, got $result")
        assertTrue(backend.ran.isEmpty())
        assertEquals(listOf("HOST_NOT_FOUND:class not found"), diags)
    }

    @Test
    fun aDeclinedNativeBehavesExactlyLikeHavingNoBackendAtAll() {
        // the whole point of the allow-list: switching a backend on must not change anything it doesn't run
        val (withBackend, diagsWith) = runOther(bridgeFrom(Recording()))
        val (without, diagsWithout) = runOther(null)
        assertEquals(without?.javaClass, withBackend?.javaClass)
        assertEquals(diagsWithout, diagsWith)
    }
}
