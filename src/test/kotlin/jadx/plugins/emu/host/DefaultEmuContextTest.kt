package jadx.plugins.emu.host

import jadx.api.JadxArgs
import jadx.api.plugins.JadxPluginContext
import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.api.EmuTarget
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.api.NativeBackend
import jadx.plugins.emu.api.NativeRequest
import jadx.plugins.emu.exec.Diagnostic
import jadx.plugins.emu.exec.DiagnosticSink
import jadx.plugins.emu.exec.plus
import jadx.plugins.emu.exec.runtime.isUnknown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The production [DefaultEmuContext], with jadx stood in for by a stub that has only input files. Mirrors upstream's
 * EmuContextTest, which runs the same scenarios on its own context class.
 */
class DefaultEmuContextTest {
    private val app = cls("Lcom/app/A;", """
        .field static n:I
    """ + staticMethod("bump()I", 1, """
            sget v0, Lcom/app/A;->n:I
            add-int/lit8 v0, v0, 1
            sput v0, Lcom/app/A;->n:I
            return v0
        """) + staticMethod("radio()Ljava/lang/String;", 1, """
            invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))

    private val patch = cls("Lcom/app/A;", staticMethod("bump()I", 1, """
            const/16 v0, 100
            return v0
        """))

    private val extra = cls("Lcom/app/B;", staticMethod("two()I", 1, """
            const/4 v0, 2
            return v0
        """))

    private val backends = NativeBackendRegistry()

    /** jadx stand-in: no decompiler (so the context parses the input file itself), one input file. */
    private fun emu(): DefaultEmuContext {
        val dex = TestDex.assembleFile(app)
        val jadx = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(JadxPluginContext::class.java)) { _, m, _ ->
            when (m.name) {
                "getArgs" -> JadxArgs().also { it.inputFiles = listOf(dex) }
                "getDecompiler" -> throw UnsupportedOperationException("jadx has not loaded anything in this test")
                else -> null
            }
        } as JadxPluginContext
        return DefaultEmuContext(jadx, EmuOptions(), backends)
    }

    @Test
    fun theSourceIsBuiltFromTheInputFiles() {
        assertNotNull(emu().source.method("Lcom/app/A;", "bump()I"))
    }

    @Test
    fun methodByDescriptorAndTarget() {
        val emu = emu()
        assertNotNull(emu.method("Lcom/app/A;->bump()I"))
        assertNull(emu.method("Lcom/app/A;->nope()I"))
        assertNull(emu.method("garbage"))
        assertNotNull(emu.method(EmuTarget("com.app.A", "bump()I")))
        assertNull(emu.method(EmuTarget("com.app.A")), "a class target has no method")
        assertNotNull(emu.method("com.app.A", "bump()I"), "the older two-argument form still works")
    }

    @Test
    fun hostAndDiagnosticsAreTheEnginesOwn() {
        val emu = emu()
        assertSame(emu.engine.host, emu.host)
        assertSame(emu.engine.diagnostics, emu.diagnostics)
    }

    @Test
    fun vmsShareStaticsAndDataflowDoesNot() {
        val emu = emu()
        val bump = emu.method("Lcom/app/A;->bump()I")!!
        assertEquals(1, emu.newVm().invoke(bump))
        assertEquals(2, emu.newVm().invoke(bump))
        assertTrue(emu.newDataflow().analyze(bump).returnValue.isUnknown())
        assertEquals(3, emu.newVm().invoke(bump))
    }

    @Test
    fun forkHasOwnStaticsButSharesPolicyAndDiagnostics() {
        val emu = emu()
        val bump = emu.method("Lcom/app/A;->bump()I")!!
        emu.newVm().invoke(bump)
        val fork = emu.fork()
        assertEquals(1, fork.newVm().invoke(fork.method("Lcom/app/A;->bump()I")!!), "the fork starts with fresh statics")
        assertEquals(2, emu.newVm().invoke(bump), "and what it did doesn't show in the parent")
        assertSame(emu.host, fork.host)
        val diags = ArrayList<Diagnostic>()
        emu.diagnostics.add(DiagnosticSink { diags.add(it) })
        val fork2 = emu.fork()
        fork2.newVm().invoke(fork2.method("Lcom/app/A;->radio()Ljava/lang/String;")!!)
        assertEquals(listOf(Diagnostic.Kind.MISSING_STUB), diags.map { it.kind }, "a fork reports to the parent's sinks")
    }

    @Test
    fun forkUsesGivenOrFreshHooks() {
        val emu = emu()
        val hooks = emu.engine.hooks
        hooks.stub("Landroid/os/Build;->getRadioVersion", "r1")
        val radio = "Lcom/app/A;->radio()Ljava/lang/String;"
        assertEquals("r1", emu.newVm().invoke(emu.method(radio)!!))
        val fresh = emu.fork()
        assertTrue(fresh.newVm().invoke(fresh.method(radio)!!).isUnknown(), "a fork without hooks starts clean")
        val shared = emu.fork(hooks = hooks)
        assertEquals("r1", shared.newVm().invoke(shared.method(radio)!!))
    }

    @Test
    fun forkOverLayeredSource() {
        val emu = emu()
        val patched = emu.fork(source = TestDex.assemble(patch) + emu.source + TestDex.assemble(extra))
        assertEquals(100, patched.newVm().invoke(patched.method("Lcom/app/A;->bump()I")!!), "the patch layer wins")
        assertEquals(2, patched.newVm().invoke(patched.method("Lcom/app/B;->two()I")!!))
        assertNotNull(patched.method("Lcom/app/A;->radio()Ljava/lang/String;"), "what the patch lacks comes from the layer behind it")
        assertEquals(1, emu.newVm().invoke(emu.method("Lcom/app/A;->bump()I")!!), "the parent is untouched")
    }

    @Test
    fun loadDexFromFileAndBytes() {
        val emu = emu()
        val file = TestDex.assembleFile(extra)
        assertNotNull(emu.loadDex(file.toPath()).method("Lcom/app/B;", "two()I"))
        assertNotNull(emu.loadDex(file.readBytes()).method("Lcom/app/B;", "two()I"))
    }

    @Test
    fun aDexLoadedFromBytesCanBeRunAfterTheTemporaryFileIsGone() {
        val emu = emu()
        val src = emu.loadDex(TestDex.assembleFile(extra).readBytes())
        val child = emu.fork(source = src)
        assertEquals(2, child.newVm().invoke(child.method("Lcom/app/B;->two()I")!!))
    }

    @Test
    fun aForkOfAForkWorks() {
        val emu = emu()
        val grandchild = emu.fork().fork()
        assertSame(emu.host, grandchild.host)
        assertEquals(1, grandchild.newVm().invoke(grandchild.method("Lcom/app/A;->bump()I")!!))
    }

    @Test
    fun nativeBackendsAreSharedWithForks() {
        backends.register("owner", object : NativeBackend {
            override val id = "x"
            override fun open(request: NativeRequest) = throw UnsupportedOperationException()
        })
        val emu = emu()
        assertEquals(listOf("x"), emu.nativeBackendIds)
        assertNotNull(emu.fork().nativeBackend("x"))
        assertEquals(listOf("x"), emu.fork().fork().nativeBackendIds)
    }
}
