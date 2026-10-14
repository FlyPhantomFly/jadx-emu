package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UNKNOWN
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InterpreterTest {
    private val c = "LT;"

    private fun run(method: String, vararg args: Any?): Any? {
        val src = TestDex.assemble(cls(c, method))
        val sig = method.substringAfter("static ").substringBefore("\n")
        return TestDex.run(src, c, sig, *args)
    }

    @Test
    fun intArithmetic() {
        val m = staticMethod("f(II)I", 4, """
            add-int v0, p0, p1
            mul-int/lit8 v0, v0, 3
            rem-int/lit8 v1, p0, 7
            sub-int v0, v0, v1
            xor-int/lit16 v0, v0, 0x1234
            shl-int/lit8 v0, v0, 2
            ushr-int/lit8 v1, p1, 1
            or-int v0, v0, v1
            return v0
        """)
        val a = 123; val b = -45
        val expected = ((((a + b) * 3 - (a % 7)) xor 0x1234) shl 2) or (b ushr 1)
        assertEquals(expected, run(m, a, b))
    }

    @Test
    fun longArithmeticUsesRegisterPairs() {
        val m = staticMethod("f(JJ)J", 9, """
            mul-long v0, p0, p2
            const-wide/16 v2, 7
            rem-long v0, v0, v2
            const/16 v4, 33
            shl-long/2addr v0, v4
            xor-long/2addr v0, p0
            return-wide v0
        """)
        val a = 0x123456789ABL; val b = -987654321L
        val expected = (((a * b) % 7L) shl 33) xor a
        assertEquals(expected, run(m, a, b))
    }

    @Test
    fun conversions() {
        val m = staticMethod("f(I)J", 4, """
            int-to-byte v0, p0
            int-to-char v1, p0
            add-int v0, v0, v1
            int-to-long v2, v0
            const-wide/32 v0, 0x100000
            add-long/2addr v2, v0
            return-wide v2
        """)
        val x = 0x1FF80
        assertEquals((x.toByte().toInt() + x.toChar().code).toLong() + 0x100000L, run(m, x))
    }

    @Test
    fun branchesAndLoop() {
        val m = staticMethod("f(I)I", 4, """
            const/4 v0, 0
            const/4 v1, 0
            :loop
            if-ge v1, p0, :done
            rem-int/lit8 v2, v1, 2
            if-nez v2, :odd
            add-int/2addr v0, v1
            goto :next
            :odd
            sub-int/2addr v0, v1
            :next
            add-int/lit8 v1, v1, 1
            goto :loop
            :done
            return v0
        """)
        var expected = 0
        for (i in 0 until 50) expected += if (i % 2 == 0) i else -i
        assertEquals(expected, run(m, 50))
    }

    @Test
    fun packedAndSparseSwitch() {
        val m = staticMethod("f(I)I", 3, """
            packed-switch p0, :packed
            sparse-switch p0, :sparse
            const/4 v0, -1
            return v0
            :p1
            const/16 v0, 10
            return v0
            :p2
            const/16 v0, 20
            return v0
            :s1
            const/16 v0, 100
            return v0
            :s2
            const/16 v0, 200
            return v0
            :packed
            .packed-switch 1
                :p1
                :p2
            .end packed-switch
            :sparse
            .sparse-switch
                1000 -> :s1
                -5 -> :s2
            .end sparse-switch
        """)
        assertEquals(10, run(m, 1))
        assertEquals(20, run(m, 2))
        assertEquals(100, run(m, 1000))
        assertEquals(200, run(m, -5))
        assertEquals(-1, run(m, 3))
    }

    @Test
    fun arraysOfEachType() {
        val m = staticMethod("f()J", 6, """
            const/4 v0, 3
            new-array v1, v0, [I
            const/4 v2, 1
            const/16 v3, 100
            aput v3, v1, v2
            new-array v2, v0, [J
            const/4 v3, 2
            const-wide/16 v4, 0x40
            aput-wide v4, v2, v3
            const/4 v3, 0
            new-array v4, v0, [B
            const/16 v5, -3
            aput-byte v5, v4, v3
            new-array v5, v0, [C
            const/16 v3, 0x41
            const/4 v0, 2
            aput-char v3, v5, v0
            const/4 v0, 1
            aget v0, v1, v0
            int-to-long v0, v0
            const/4 v3, 2
            aget-wide v2, v2, v3
            add-long/2addr v0, v2
            const/4 v3, 0
            aget-byte v3, v4, v3
            int-to-long v2, v3
            add-long/2addr v0, v2
            const/4 v3, 2
            aget-char v3, v5, v3
            int-to-long v2, v3
            add-long/2addr v0, v2
            array-length v3, v5
            int-to-long v2, v3
            add-long/2addr v0, v2
            return-wide v0
        """)
        assertEquals(100L + 0x40L - 3L + 0x41L + 3L, run(m))
    }

    @Test
    fun fillArrayData() {
        val m = staticMethod("f()[I", 2, """
            const/4 v0, 4
            new-array v0, v0, [I
            fill-array-data v0, :data
            return-object v0
            :data
            .array-data 4
                1 -2 300 -40000
            .end array-data
        """)
        assertArrayEquals(intArrayOf(1, -2, 300, -40000), run(m) as IntArray)
    }

    @Test
    fun arrayOutOfBoundsThrowsEmulatedException() {
        val m = staticMethod("f()I", 3, """
            const/4 v0, 1
            new-array v1, v0, [I
            const/4 v2, 5
            aget v0, v1, v2
            return v0
        """)
        val src = TestDex.assemble(cls(c, m))
        val vm = TestDex.vm(src)
        val ex = assertThrows(DvmThrowable::class.java) { TestDex.call(vm, src, c, "f()I") }
        assertEquals("Ljava/lang/ArrayIndexOutOfBoundsException;", ex.type)
        assertSame(UNKNOWN, TestDex.run(src, c, "f()I"))
    }

    @Test
    fun stringsThroughHostCalls() {
        val m = staticMethod("f(Ljava/lang/String;)Ljava/lang/String;", 4, """
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            move-result-object v0
            invoke-virtual {p0}, Ljava/lang/String;->length()I
            move-result v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
            move-result-object v0
            const/4 v1, 0
            invoke-virtual {p0, v1}, Ljava/lang/String;->charAt(I)C
            move-result v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/String;->toUpperCase()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)
        assertEquals("HELLO5H", run(m, "hello"))
    }

    @Test
    fun newStringFromCharArray() {
        val m = staticMethod("f()Ljava/lang/String;", 3, """
            const/4 v0, 2
            new-array v0, v0, [C
            const/16 v1, 0x68
            const/4 v2, 0
            aput-char v1, v0, v2
            const/16 v1, 0x69
            const/4 v2, 1
            aput-char v1, v0, v2
            new-instance v1, Ljava/lang/String;
            invoke-direct {v1, v0}, Ljava/lang/String;-><init>([C)V
            return-object v1
        """)
        assertEquals("hi", run(m))
    }

    @Test
    fun objectsFieldsAndClinitOrder() {
        val base = cls("LBase;", """
            .field public static order:Ljava/lang/String;
            .method static constructor <clinit>()V
                .registers 1
                const-string v0, "base"
                sput-object v0, LBase;->order:Ljava/lang/String;
                return-void
            .end method
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
        """)
        val sub = cls("LSub;", """
            .field public value:I
            .method static constructor <clinit>()V
                .registers 2
                sget-object v0, LBase;->order:Ljava/lang/String;
                const-string v1, "+sub"
                invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                sput-object v0, LBase;->order:Ljava/lang/String;
                return-void
            .end method
            .method public constructor <init>(I)V
                .registers 2
                invoke-direct {p0}, LBase;-><init>()V
                iput p1, p0, LSub;->value:I
                return-void
            .end method
        """, superType = "LBase;")
        val t = cls(c, staticMethod("f()Ljava/lang/String;", 4, """
            const/16 v0, 42
            new-instance v1, LSub;
            invoke-direct {v1, v0}, LSub;-><init>(I)V
            iget v0, v1, LSub;->value:I
            sget-object v2, LBase;->order:Ljava/lang/String;
            new-instance v3, Ljava/lang/StringBuilder;
            invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V
            invoke-virtual {v3, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
            invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))
        val src = TestDex.assemble(base, sub, t)
        val vm = TestDex.vm(src)
        assertEquals("base+sub42", TestDex.call(vm, src, c, "f()Ljava/lang/String;"))
        assertEquals("base+sub42", TestDex.call(vm, src, c, "f()Ljava/lang/String;"))
        assertEquals(setOf("LBase;", "LSub;", c), vm.initialized())
    }

    @Test
    fun virtualDispatchAndSuper() {
        val animal = cls("LAnimal;", """
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public sound()Ljava/lang/String;
                .registers 2
                const-string v0, "..."
                return-object v0
            .end method
        """)
        val dog = cls("LDog;", """
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, LAnimal;-><init>()V
                return-void
            .end method
            .method public sound()Ljava/lang/String;
                .registers 3
                invoke-super {p0}, LAnimal;->sound()Ljava/lang/String;
                move-result-object v0
                const-string v1, "woof"
                invoke-virtual {v1, v0}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
        """, superType = "LAnimal;")
        val t = cls(c, staticMethod("f(Z)Ljava/lang/String;", 3, """
            if-eqz p0, :animal
            new-instance v0, LDog;
            invoke-direct {v0}, LDog;-><init>()V
            goto :call
            :animal
            new-instance v0, LAnimal;
            invoke-direct {v0}, LAnimal;-><init>()V
            :call
            invoke-virtual {v0}, LAnimal;->sound()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))
        val src = TestDex.assemble(animal, dog, t)
        assertEquals("woof...", TestDex.run(src, c, "f(Z)Ljava/lang/String;", true))
        assertEquals("...", TestDex.run(src, c, "f(Z)Ljava/lang/String;", false))
    }

    @Test
    fun interfaceCallAndInstanceOf() {
        val iface = ".class public interface abstract LGreeter;\n.super Ljava/lang/Object;\n" +
            ".method public abstract greet()Ljava/lang/String;\n.end method\n"
        val impl = """
            .class public LHello;
            .super Ljava/lang/Object;
            .implements LGreeter;
            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method
            .method public greet()Ljava/lang/String;
                .registers 2
                const-string v0, "hi"
                return-object v0
            .end method
        """.trimIndent()
        val t = cls(c, staticMethod("f()Ljava/lang/String;", 3, """
            new-instance v0, LHello;
            invoke-direct {v0}, LHello;-><init>()V
            instance-of v1, v0, LGreeter;
            if-nez v1, :ok
            const-string v0, "not a greeter"
            return-object v0
            :ok
            check-cast v0, LGreeter;
            invoke-interface {v0}, LGreeter;->greet()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))
        val src = TestDex.assemble(iface, impl, t)
        assertEquals("hi", TestDex.run(src, c, "f()Ljava/lang/String;"))
    }

    @Test
    fun exceptionCaughtInSameMethod() {
        val m = staticMethod("f(I)Ljava/lang/String;", 3, """
            :try_start
            if-nez p0, :ok
            new-instance v0, Ljava/lang/IllegalStateException;
            const-string v1, "zero"
            invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
            throw v0
            :ok
            const-string v0, "fine"
            return-object v0
            :try_end
            .catch Ljava/lang/IllegalStateException; {:try_start .. :try_end} :handler
            :handler
            move-exception v0
            invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;
            move-result-object v0
            const-string v1, "caught "
            invoke-virtual {v1, v0}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        """)
        assertEquals("fine", run(m, 1))
        assertEquals("caught zero", run(m, 0))
    }

    @Test
    fun exceptionPropagatesToCallerWithFinally() {
        val t = cls(c, staticMethod("thrower()V", 2, """
            new-instance v0, Ljava/lang/RuntimeException;
            const-string v1, "boom"
            invoke-direct {v0, v1}, Ljava/lang/RuntimeException;-><init>(Ljava/lang/String;)V
            throw v0
        """) + staticMethod("f()Ljava/lang/String;", 3, """
            const-string v0, "start"
            :try_start
            invoke-static {}, LT;->thrower()V
            const-string v0, "unreached"
            :try_end
            .catchall {:try_start .. :try_end} :fin
            goto :done
            :fin
            move-exception v1
            const-string v2, "+finally"
            invoke-virtual {v0, v2}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            throw v1
            :done
            return-object v0
        """) + staticMethod("g()Ljava/lang/String;", 2, """
            :try_start
            invoke-static {}, LT;->f()Ljava/lang/String;
            move-result-object v0
            return-object v0
            :try_end
            .catch Ljava/lang/RuntimeException; {:try_start .. :try_end} :h
            :h
            move-exception v0
            invoke-virtual {v0}, Ljava/lang/Throwable;->getMessage()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """))
        val src = TestDex.assemble(t)
        assertEquals("boom", TestDex.run(src, c, "g()Ljava/lang/String;"))
        val vm = TestDex.vm(src)
        val ex = assertThrows(DvmThrowable::class.java) { TestDex.call(vm, src, c, "f()Ljava/lang/String;") }
        assertEquals("Ljava/lang/RuntimeException;", ex.type)
        assertSame(UNKNOWN, TestDex.run(src, c, "f()Ljava/lang/String;"))
    }

    @Test
    fun unknownArgumentPropagates() {
        val m = staticMethod("f(I)I", 2, """
            mul-int/lit8 v0, p0, 2
            return v0
        """)
        val r = run(m, UnknownVal("I"))
        assertInstanceOf(UnknownVal::class.java, r)
        assertEquals(6, run(m, 3))
    }

    @Test
    fun unknownBranchAbortsToUnknownResult() {
        val m = staticMethod("f(I)I", 2, """
            if-eqz p0, :zero
            const/4 v0, 1
            return v0
            :zero
            const/4 v0, 0
            return v0
        """)
        val src = TestDex.assemble(cls(c, m))
        val diags = ArrayList<Diagnostic>()
        val vm = TestDex.context(src).apply { diagnostics.add { diags.add(it) } }.newVm()
        val r = TestDex.call(vm, src, c, "f(I)I", UnknownVal("I"))
        assertInstanceOf(UnknownVal::class.java, r)
        assertEquals("I", (r as UnknownVal).type)
        val abort = diags.single { it.kind == Diagnostic.Kind.ABORTED }
        assertEquals("unknown branch", abort.detail)
        assertEquals("$c->f(I)I", "${abort.ref.declClass}->${abort.ref.shortId}")
    }

    @Test
    fun dvmObjectFieldsVisibleToHost() {
        val p = cls("LP;", """
            .field public x:I
            .field public name:Ljava/lang/String;
            .method public constructor <init>(ILjava/lang/String;)V
                .registers 3
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                iput p1, p0, LP;->x:I
                iput-object p2, p0, LP;->name:Ljava/lang/String;
                return-void
            .end method
        """)
        val t = cls(c, staticMethod("f()LP;", 3, """
            const/16 v0, 7
            const-string v1, "seven"
            new-instance v2, LP;
            invoke-direct {v2, v0, v1}, LP;-><init>(ILjava/lang/String;)V
            return-object v2
        """))
        val src = TestDex.assemble(p, t)
        val obj = TestDex.run(src, c, "f()LP;") as DvmObject
        assertEquals("LP;", obj.type)
        assertEquals(7, obj.fields["LP;.x"])
        assertEquals("seven", obj.fields["LP;.name"])
    }
}
