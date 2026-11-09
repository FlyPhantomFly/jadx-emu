package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.isUnknown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Hooks.hookAfter` is upstream's return-value hook; `Hooks.after` is ours. Upstream's own tests cover the basics
 * (HooksStubsTest). These cover every path a call can complete on, and the two kinds used together.
 */
class HookAfterTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, staticMethod("twice(I)I", 2, """
            mul-int/lit8 v0, p0, 2
            return v0
        """) + staticMethod("f(I)I", 2, """
            invoke-static {p0}, LT;->twice(I)I
            move-result v0
            add-int/lit8 v0, v0, 1
            return v0
        """) + staticMethod("upper(Ljava/lang/String;)Ljava/lang/String;", 1, """
            invoke-virtual {p0}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;
            move-result-object p0
            return-object p0
        """) + staticMethod("boom()I", 2, """
            new-instance v0, Ljava/lang/RuntimeException;
            const-string v1, "boom"
            invoke-direct {v0, v1}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;)V
            throw v0
        """) + staticMethod("callBoom()I", 1, """
            invoke-static {}, LT;->boom()I
            move-result v0
            return v0
        """) + staticMethod("build()Ljava/lang/String;", 2, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "hi"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("cname()Ljava/lang/String;", 1, """
            const-class v0, Ljava/lang/String;
            invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("viaNative(I)I", 2, """
            invoke-static {p0}, LT;->nat(I)I
            move-result v0
            return v0
        """) + ".method public static native nat(I)I\n.end method\n"))

    private fun call(hooks: Hooks, m: String, vararg a: Any?): Any? = TestDex.call(TestDex.vm(source, hooks = hooks), source, c, m, *a)

    // ---- every path a call can complete on ----

    @Test
    fun hookAfterSeesAndRewritesAnEmulatedMethodsResult() {
        val hooks = Hooks()
        val seen = ArrayList<Any?>()
        hooks.hookAfter("$c->twice") { seen.add(result); replace(100) }
        assertEquals(101, call(hooks, "f(I)I", 7))
        assertEquals(listOf<Any?>(14), seen)
    }

    @Test
    fun hookAfterRunsOnHostMethodResults() {
        val hooks = Hooks()
        hooks.hookAfter("Ljava/lang/String;->toUpperCase") { replace(result.toString() + "?") }
        assertEquals("ABC?", call(hooks, "upper(Ljava/lang/String;)Ljava/lang/String;", "abc"))
    }

    @Test
    fun hookAfterRunsOnReflectionResults() {
        val hooks = Hooks()
        hooks.hookAfter("Ljava/lang/Class;->getName") { replace(result.toString() + "!") }
        assertEquals("java.lang.String!", call(hooks, "cname()Ljava/lang/String;"))
    }

    @Test
    fun hookAfterRunsOnNativeBridgeResults() {
        val hooks = Hooks()
        val seen = ArrayList<Any?>()
        hooks.hookAfter("$c->nat") { seen.add(result); replace((result as Int) + 1000) }
        val bridge = object : NativeBridge {
            override fun call(className: String, methodName: String, signature: String, args: List<Any?>, receiver: Any?): Any? =
                (args[0] as Int) * 3
        }
        val vm = TestDex.context(source, hooks = hooks).newVm(nativeBridge = bridge, androidEnvUnknown = false)
        assertEquals(1021, TestDex.call(vm, source, c, "viaNative(I)I", 7))
        assertEquals(listOf<Any?>(21), seen)
    }

    @Test
    fun hookAfterSeesTheConstructionOfAHostObjectWithANullResult() {
        val hooks = Hooks()
        val seen = ArrayList<Any?>()
        hooks.hookAfter("Ljava/lang/StringBuilder;-><init>") { seen.add(result) }
        assertEquals("hi", call(hooks, "build()Ljava/lang/String;"))
        assertEquals(1, seen.size)
        assertNull(seen[0])
    }

    @Test
    fun hookAfterRunsOnAReplacedCallAndSeesTheReplacement() {
        val hooks = Hooks()
        val order = ArrayList<String>()
        hooks.hook("$c->twice") { order.add("before"); replace(10) }
        hooks.hookAfter("$c->twice") { order.add("after " + result) }
        assertEquals(11, call(hooks, "f(I)I", 7))
        assertEquals(listOf("before", "after 10"), order)
    }

    @Test
    fun hookAfterIsNotRunForACallThatThrew() {
        val hooks = Hooks()
        var ran = 0
        hooks.hookAfter("$c->boom") { ran++ }
        assertThrows(DvmThrowable::class.java) { call(hooks, "callBoom()I") }
        assertEquals(0, ran)
    }

    // ---- chain rules ----

    @Test
    fun theFirstHandlerToReplaceEndsTheChain() {
        val hooks = Hooks()
        val ran = ArrayList<String>()
        hooks.hookAfter("$c->twice") { ran.add("older") }
        hooks.hookAfter("$c->twice") { ran.add("newer"); replace(0) }
        assertEquals(1, call(hooks, "f(I)I", 7))
        assertEquals(listOf("newer"), ran, "the older handler never ran")
    }

    @Test
    fun handlersThatOnlyObserveAllRunNewestFirst() {
        val hooks = Hooks()
        val ran = ArrayList<String>()
        hooks.hookAfter("$c->twice") { ran.add("older") }
        hooks.hookAfter("$c->twice") { ran.add("newer") }
        assertEquals(15, call(hooks, "f(I)I", 7))
        assertEquals(listOf("newer", "older"), ran)
    }

    @Test
    fun exactSignatureHandlersRunBeforeNameOnlyOnes() {
        val hooks = Hooks()
        val ran = ArrayList<String>()
        hooks.hookAfter("$c->twice") { ran.add("by name") }
        hooks.hookAfter("$c->twice(I)I") { ran.add("exact") }
        call(hooks, "f(I)I", 7)
        assertEquals(listOf("exact", "by name"), ran)
    }

    @Test
    fun aRewrittenResultIsCoercedToTheDeclaredReturnType() {
        val hooks = Hooks()
        hooks.hookAfter("$c->twice") { replace(100L) }
        assertEquals(101, call(hooks, "f(I)I", 7))
    }

    @Test
    fun aFailingHandlerIsReportedAndTheResultBecomesUnknown() {
        val hooks = Hooks()
        hooks.hookAfter("$c->twice") { throw IllegalStateException("nope") }
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(source, hooks = hooks).apply { diagnostics.add { diags.add(it) } }.newVm(androidEnvUnknown = false)
        assertTrue(TestDex.call(vm, source, c, "f(I)I", 7).isUnknown())
        assertTrue(diags.any { it.kind == Diagnostic.Kind.HANDLER_FAILED })
    }

    @Test
    fun removingARegistrationStopsTheHandler() {
        val hooks = Hooks()
        var ran = 0
        val reg = hooks.hookAfter("$c->twice") { ran++ }
        call(hooks, "f(I)I", 1)
        reg.remove()
        call(hooks, "f(I)I", 1)
        assertEquals(1, ran)
    }

    @Test
    fun clearingAScopeRemovesOnlyItsOwnHandlers() {
        val hooks = Hooks()
        val ran = ArrayList<String>()
        hooks.hookAfter("$c->twice") { ran.add("root") }
        val scope = hooks.scope()
        scope.hookAfter("$c->twice") { ran.add("scoped") }
        scope.clear()
        call(hooks, "f(I)I", 1)
        assertEquals(listOf("root"), ran)
    }

    // ---- hookAfter and after together ----

    @Test
    fun afterRunsFirstAndHookAfterSeesItsResult() {
        val hooks = Hooks()
        hooks.hookAfter("$c->twice") { replace((result as Int) * 10) }
        hooks.after("$c->twice") { result -> (result as Int) + 1 }
        // twice(7) = 14, after +1 = 15, hookAfter x10 = 150, f adds 1
        assertEquals(151, call(hooks, "f(I)I", 7))
    }

    @Test
    fun onAReplacedCallOnlyHookAfterRuns() {
        val hooks = Hooks()
        val ran = ArrayList<String>()
        hooks.hook("$c->twice") { replace(10) }
        hooks.after("$c->twice") { result -> ran.add("after"); result }
        hooks.hookAfter("$c->twice") { ran.add("hookAfter") }
        assertEquals(11, call(hooks, "f(I)I", 7))
        assertEquals(listOf("hookAfter"), ran)
    }

    @Test
    fun onHostConstructionOnlyHookAfterRuns() {
        val hooks = Hooks()
        val ran = ArrayList<String>()
        hooks.after("Ljava/lang/StringBuilder;-><init>") { result -> ran.add("after"); result }
        hooks.hookAfter("Ljava/lang/StringBuilder;-><init>") { ran.add("hookAfter") }
        call(hooks, "build()Ljava/lang/String;")
        assertEquals(listOf("hookAfter"), ran)
    }

    @Test
    fun aFailingAfterKeepsTheRealResultButAFailingHookAfterDoesNot() {
        val a = Hooks()
        a.after("$c->twice") { throw IllegalStateException("x") }
        assertEquals(15, call(a, "f(I)I", 7), "after keeps the real result")
        val b = Hooks()
        b.hookAfter("$c->twice") { throw IllegalStateException("x") }
        assertTrue(call(b, "f(I)I", 7).isUnknown(), "hookAfter makes it unknown")
    }

    @Test
    fun withNeitherKindRegisteredNothingChanges() {
        assertEquals(15, call(Hooks(), "f(I)I", 7))
    }
}
