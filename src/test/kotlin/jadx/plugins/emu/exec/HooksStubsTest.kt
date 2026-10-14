package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HooksStubsTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, staticMethod("twice(I)I", 2, """
            mul-int/lit8 v0, p0, 2
            return v0
        """) + staticMethod("f(I)I", 2, """
            invoke-static {p0}, LT;->twice(I)I
            move-result v0
            add-int/lit8 v0, v0, 1
            return v0
        """) + staticMethod("radio()Ljava/lang/String;", 1, """
            invoke-static {}, Landroid/os/Build;->getRadioVersion()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("model()Ljava/lang/String;", 1, """
            sget-object v0, Landroid/os/Build;->MODEL:Ljava/lang/String;
            return-object v0
        """) + staticMethod("caller()Ljava/lang/String;", 3, """
            invoke-static {}, Ljava/lang/Thread;->currentThread()Ljava/lang/Thread;
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/Thread;->getStackTrace()[Ljava/lang/StackTraceElement;
            move-result-object v0
            const/4 v1, 2
            aget-object v0, v0, v1
            invoke-virtual {v0}, Ljava/lang/StackTraceElement;->getClassName()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("upper(Ljava/lang/String;)Ljava/lang/String;", 1, """
            invoke-virtual {p0}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;
            move-result-object p0
            return-object p0
        """)))

    @Test
    fun hookReplacesResultAndSeesArguments() {
        val hooks = HookRegistry()
        val seen = ArrayList<List<Any?>>()
        val id = hooks.add("$c->twice(I)I") { call ->
            seen.add(call.args.toList())
            call.replace(100)
        }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(101, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(listOf(listOf(7)), seen)
        assertTrue(hooks.remove(id))
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
    }

    @Test
    fun hookWithoutReplaceLetsOriginalRun() {
        val hooks = HookRegistry()
        var calls = 0
        hooks.add("$c->twice(I)I") { calls++ }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(1, calls)
    }

    @Test
    fun hookOnHostMethodAndReceiver() {
        val hooks = HookRegistry()
        var receiver: Any? = null
        hooks.add("Ljava/lang/String;->toUpperCase()Ljava/lang/String;") { call ->
            receiver = call.receiver
            call.replace("hooked")
        }
        val vm = TestDex.vm(source, hooks = hooks)
        assertEquals("hooked", TestDex.call(vm, source, c, "upper(Ljava/lang/String;)Ljava/lang/String;", "abc"))
        assertEquals("abc", receiver)
    }

    @Test
    fun frameworkMethodWithoutStubIsUnknownAndReported() {
        val diags = ArrayList<Diagnostic>()
        val ctx = TestDex.context(source).apply { diagnostics.add { diags.add(it) } }
        val vm = ctx.newVm(androidEnvUnknown = false)
        val r = TestDex.call(vm, source, c, "radio()Ljava/lang/String;")
        assertInstanceOf(UnknownVal::class.java, r)
        val d = diags.single { it.kind == Diagnostic.Kind.MISSING_STUB }
        assertEquals("Landroid/os/Build;", d.ref.declClass)
        assertEquals("getRadioVersion()Ljava/lang/String;", d.ref.shortId)
        assertEquals("$c->radio()Ljava/lang/String;@0", d.site)
    }

    @Test
    fun registeredStubAnswersFrameworkMethod() {
        val stubs = AndroidStubs()
        stubs.registerMethod("Landroid/os/Build;", "getRadioVersion") { _, _ -> "1.2.3" }
        val vm = TestDex.vm(source, android = stubs)
        assertEquals("1.2.3", TestDex.call(vm, source, c, "radio()Ljava/lang/String;"))
        stubs.clear()
        assertInstanceOf(UnknownVal::class.java, TestDex.call(TestDex.vm(source, android = stubs), source, c, "radio()Ljava/lang/String;"))
    }

    @Test
    fun stubReturningNotHandledFallsThrough() {
        val stubs = AndroidStubs()
        stubs.registerMethod("Landroid/os/Build;", "getRadioVersion") { _, _ -> NotHandled }
        val vm = TestDex.vm(source, android = stubs)
        assertInstanceOf(UnknownVal::class.java, TestDex.call(vm, source, c, "radio()Ljava/lang/String;"))
    }

    @Test
    fun buildFieldsComeFromEnvOrRegisteredValue() {
        val env = AndroidEnv(model = "TestPhone")
        val stubs = AndroidStubs(env)
        assertEquals("TestPhone", TestDex.call(TestDex.vm(source, android = stubs), source, c, "model()Ljava/lang/String;"))
        stubs.registerField("Landroid/os/Build;", "MODEL", "Override")
        assertEquals("Override", TestDex.call(TestDex.vm(source, android = stubs), source, c, "model()Ljava/lang/String;"))
        val unknownEnv = TestDex.context(source, android = AndroidStubs(env)).newVm(androidEnvUnknown = true)
        assertInstanceOf(UnknownVal::class.java, TestDex.call(unknownEnv, source, c, "model()Ljava/lang/String;"))
    }

    @Test
    fun syntheticCallerShapesStackTrace() {
        val stubs = AndroidStubs()
        stubs.syntheticCaller = "com.example.App"
        val vm = TestDex.vm(source, android = stubs)
        assertEquals("com.example.App", TestDex.call(vm, source, c, "caller()Ljava/lang/String;"))
    }

    @Test
    fun execHookSeesEnterStepExitInOrder() {
        val events = ArrayList<String>()
        val hook = object : ExecHook {
            override fun onEnter(method: DexMethod, frame: Frame) { events.add("enter ${method.ref.name}") }
            override fun onStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int) {
                if (events.last() != "step ${method.ref.name}") events.add("step ${method.ref.name}")
            }
            override fun onExit(method: DexMethod) { events.add("exit ${method.ref.name}") }
        }
        val vm = TestDex.vm(source, hook = hook)
        assertEquals(15, TestDex.call(vm, source, c, "f(I)I", 7))
        assertEquals(listOf("enter f", "step f", "enter twice", "step twice", "exit twice", "step f", "exit f"), events)
    }
}
