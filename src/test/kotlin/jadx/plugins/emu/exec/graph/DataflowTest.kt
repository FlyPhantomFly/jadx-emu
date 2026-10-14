package jadx.plugins.emu.exec.graph

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataflowTest {
    private val c = "LT;"

    private val source = TestDex.assemble(cls(c, """
        .field public static key:I = 3
        .field public static counter:I
    """ + staticMethod("dec(Ljava/lang/String;I)Ljava/lang/String;", 6, """
            invoke-virtual {p0}, Ljava/lang/String;->toCharArray()[C
            move-result-object v0
            const/4 v1, 0
            :loop
            array-length v2, v0
            if-ge v1, v2, :done
            aget-char v2, v0, v1
            xor-int/2addr v2, p1
            int-to-char v2, v2
            aput-char v2, v0, v1
            add-int/lit8 v1, v1, 1
            goto :loop
            :done
            new-instance v1, Ljava/lang/String;
            invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
            return-object v1
        """) + staticMethod("sameOnBothPaths(Z)Ljava/lang/String;", 3, """
            if-eqz p0, :b
            const/16 v0, 3
            goto :join
            :b
            const/16 v0, 3
            :join
            const-string v1, "jmoon"
            invoke-static {v1, v0}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v1
            return-object v1
        """) + staticMethod("differentOnPaths(Z)Ljava/lang/String;", 3, """
            if-eqz p0, :b
            const/16 v0, 3
            goto :join
            :b
            const/16 v0, 5
            :join
            const-string v1, "jmoon"
            invoke-static {v1, v0}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v1
            return-object v1
        """) + staticMethod("viaConstantStatic()Ljava/lang/String;", 3, """
            sget v0, LT;->key:I
            const-string v1, "jmoon"
            invoke-static {v1, v0}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v1
            return-object v1
        """) + staticMethod("viaMutableStatic()Ljava/lang/String;", 3, """
            sget v0, LT;->counter:I
            const-string v1, "jmoon"
            invoke-static {v1, v0}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v1
            return-object v1
        """) + staticMethod("touch()V", 1, """
            const/4 v0, 1
            sput v0, LT;->counter:I
            return-void
        """) + staticMethod("loopBuilt(Z)Ljava/lang/String;", 5, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const/4 v1, 0
            :loop
            const/4 v2, 3
            if-ge v1, v2, :done
            const/16 v3, 0x61
            add-int/2addr v3, v1
            int-to-char v3, v3
            invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;
            add-int/lit8 v1, v1, 1
            goto :loop
            :done
            if-eqz p0, :skip
            const-string v2, "!"
            invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            :skip
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)))

    private fun analyze(method: String): DataflowResult =
        Dataflow(TestDex.context(source).newVm()).analyze(TestDex.method(source, c, method))

    private fun argsAtDecCall(method: String): List<Any?> {
        val res = analyze(method)
        val call = res.method.insns.first { (it.ref as? jadx.plugins.emu.exec.model.MethodRef)?.name == "dec" }
        return call.regs.map { res.regInAtOffset(call.offset, it) }
    }

    @Test
    fun constantMergedFromBothBranchesIsKnown() {
        assertEquals(listOf("jmoon", 3), argsAtDecCall("sameOnBothPaths(Z)Ljava/lang/String;"))
    }

    @Test
    fun conflictingConstantsBecomeUnknownWithoutAbort() {
        val res = analyze("differentOnPaths(Z)Ljava/lang/String;")
        val args = argsAtDecCall("differentOnPaths(Z)Ljava/lang/String;")
        assertEquals("jmoon", args[0])
        assertInstanceOf(UnknownVal::class.java, args[1])
        assertTrue(res.complete)
        assertTrue(res.reachable.all { it })
    }

    @Test
    fun staticReadsUseClinitSnapshotUnlessMutable() {
        assertEquals(listOf("jmoon", 3), argsAtDecCall("viaConstantStatic()Ljava/lang/String;"))
        assertInstanceOf(UnknownVal::class.java, argsAtDecCall("viaMutableStatic()Ljava/lang/String;")[1])
    }

    @Test
    fun concreteCallDecodesWhatDataflowOnlyTracks() {
        val vm = TestDex.context(source).newVm()
        assertEquals("inllm", TestDex.call(vm, source, c, "dec(Ljava/lang/String;I)Ljava/lang/String;", "jmoon", 3))
        assertEquals("inllm", TestDex.call(vm, source, c, "sameOnBothPaths(Z)Ljava/lang/String;", true))
    }

    @Test
    fun preciseTraceVisitsBothSidesOfUnknownBranch() {
        val ctx = TestDex.context(source)
        val m = TestDex.method(source, c, "loopBuilt(Z)Ljava/lang/String;")
        val facts = PreciseTrace(ctx.newVm()).trace(m)
        assertTrue(facts.complete)
        val offsets = m.insns.map { it.offset }
        assertEquals(offsets.toSet(), facts.visitedOffsets)
        val branch = m.insns.first { it.opcode == jadx.api.plugins.input.insns.Opcode.IF_EQZ }
        assertEquals(2, facts.takenTargets[branch.offset]?.size)
    }
}
