package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmLimitsTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, staticMethod("spin()I", 1, """
            const/4 v0, 0
            :loop
            add-int/lit8 v0, v0, 1
            goto :loop
        """) + staticMethod("recurse(I)I", 2, """
            add-int/lit8 v0, p0, 1
            invoke-static {v0}, LT;->recurse(I)I
            move-result v0
            return v0
        """) + staticMethod("now()J", 2, """
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v0
            return-wide v0
        """) + staticMethod("missing()I", 1, """
            invoke-static {}, Lkotlin/jvm/internal/Intrinsics;->nothing()I
            move-result v0
            return v0
        """) + staticMethod("callsNow()J", 2, """
            invoke-static {}, LT;->now()J
            move-result-wide v0
            return-wide v0
        """)))

    private fun diags(limits: ExecLimits = ExecLimits()): Pair<Vm, List<Diagnostic>> {
        val list = ArrayList<Diagnostic>()
        val vm = TestDex.context(source, limits).apply { diagnostics.add { list.add(it) } }.newVm(limits)
        return vm to list
    }

    @Test
    fun stepLimitAbortsInfiniteLoop() {
        val (vm, d) = diags(ExecLimits(maxSteps = 1000, maxMillis = 10_000))
        val start = System.nanoTime()
        assertSame(UNKNOWN, vm.invoke(TestDex.method(source, c, "spin()I")))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertEquals("step limit", d.single { it.kind == Diagnostic.Kind.ABORTED }.detail)
    }

    @Test
    fun timeLimitAbortsInfiniteLoop() {
        val (vm, d) = diags(ExecLimits(maxSteps = Int.MAX_VALUE, maxMillis = 200))
        val start = System.nanoTime()
        assertSame(UNKNOWN, vm.invoke(TestDex.method(source, c, "spin()I")))
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsed in 150..5_000, "elapsed $elapsed ms")
        assertEquals("time limit", d.single { it.kind == Diagnostic.Kind.ABORTED }.detail)
    }

    @Test
    fun depthLimitStopsRecursion() {
        val (vm, d) = diags(ExecLimits(maxDepth = 8, maxSteps = 100_000))
        val r = vm.invoke(TestDex.method(source, c, "recurse(I)I"), listOf(0))
        assertInstanceOf(UnknownVal::class.java, r)
        assertTrue(d.any { it.kind == Diagnostic.Kind.ABORTED && it.detail == "depth limit" })
    }

    @Test
    fun deniedHostCallIsReportedWithSite() {
        val (vm, d) = diags()
        val r = TestDex.call(vm, source, c, "callsNow()J")
        assertInstanceOf(UnknownVal::class.java, r)
        val denied = d.single { it.kind == Diagnostic.Kind.HOST_DENIED }
        assertEquals("Ljava/lang/System;->currentTimeMillis()J", "${denied.ref.declClass}->${denied.ref.shortId}")
        assertEquals("$c->now()J@0", denied.site)
        assertTrue(denied.message.contains("denied by host policy"))
    }

    @Test
    fun hostCallAllowedWhenTimeRestrictionIsOff() {
        val vm = TestDex.vm(source, host = HostBoundary(restrictTime = false))
        val r = TestDex.call(vm, source, c, "now()J")
        assertInstanceOf(java.lang.Long::class.java, r)
        assertTrue((r as Long) > 1_600_000_000_000L)
    }

    @Test
    fun classOutsideNamespaceIsReported() {
        val (vm, d) = diags()
        assertInstanceOf(UnknownVal::class.java, TestDex.call(vm, source, c, "missing()I"))
        val out = d.single { it.kind == Diagnostic.Kind.HOST_OUTSIDE_NAMESPACE }
        assertEquals("Lkotlin/jvm/internal/Intrinsics;", out.ref.declClass)
    }

    @Test
    fun perVmSinkOverridesContextSinks() {
        val ctxList = ArrayList<Diagnostic>()
        val vmList = ArrayList<Diagnostic>()
        val ctx = TestDex.context(source).apply { diagnostics.add { ctxList.add(it) } }
        val vm = ctx.newVm(diagnostics = listOf(DiagnosticSink { vmList.add(it) }))
        TestDex.call(vm, source, c, "now()J")
        assertTrue(ctxList.isEmpty())
        assertEquals(1, vmList.count { it.kind == Diagnostic.Kind.HOST_DENIED })
    }
}
