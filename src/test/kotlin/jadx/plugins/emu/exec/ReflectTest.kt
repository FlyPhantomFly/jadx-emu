package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UnknownVal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ReflectTest {
    private val c = "LT;"

    private val target = cls("LTarget;", """
        .field public static counter:I = 5
        .field public tag:Ljava/lang/String;
        .method public constructor <init>(Ljava/lang/String;)V
            .registers 2
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput-object p1, p0, LTarget;->tag:Ljava/lang/String;
            return-void
        .end method
        .method public static add(II)I
            .registers 3
            add-int v0, p0, p1
            return v0
        .end method
        .method public describe()Ljava/lang/String;
            .registers 3
            iget-object v0, p0, LTarget;->tag:Ljava/lang/String;
            const-string v1, "!"
            invoke-virtual {v0, v1}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v0
            return-object v0
        .end method
    """)

    private val t = cls(c, staticMethod("viaMethod()I", 6, """
            const-string v0, "Target"
            invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            const-string v1, "add"
            const/4 v2, 2
            new-array v2, v2, [Ljava/lang/Class;
            sget-object v3, Ljava/lang/Integer;->TYPE:Ljava/lang/Class;
            const/4 v4, 0
            aput-object v3, v2, v4
            const/4 v4, 1
            aput-object v3, v2, v4
            invoke-virtual {v0, v1, v2}, Ljava/lang/Class;->getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            move-result-object v0
            const/4 v1, 2
            new-array v1, v1, [Ljava/lang/Object;
            const/16 v2, 40
            invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v2
            const/4 v3, 0
            aput-object v2, v1, v3
            const/4 v2, 2
            invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v2
            const/4 v3, 1
            aput-object v2, v1, v3
            const/4 v2, 0
            invoke-virtual {v0, v2, v1}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Ljava/lang/Integer;
            invoke-virtual {v0}, Ljava/lang/Integer;->intValue()I
            move-result v0
            return v0
        """) + staticMethod("viaField()I", 4, """
            const-string v0, "Target"
            invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            const-string v1, "counter"
            invoke-virtual {v0, v1}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
            move-result-object v0
            const/4 v1, 0
            invoke-virtual {v0, v1}, Ljava/lang/reflect/Field;->getInt(Ljava/lang/Object;)I
            move-result v2
            add-int/lit8 v2, v2, 10
            invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Field;->setInt(Ljava/lang/Object;I)V
            sget v0, LTarget;->counter:I
            return v0
        """) + staticMethod("viaConstructor()Ljava/lang/String;", 5, """
            const-string v0, "Target"
            invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            const/4 v1, 1
            new-array v1, v1, [Ljava/lang/Class;
            const-class v2, Ljava/lang/String;
            const/4 v3, 0
            aput-object v2, v1, v3
            invoke-virtual {v0, v1}, Ljava/lang/Class;->getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;
            move-result-object v0
            const/4 v1, 1
            new-array v1, v1, [Ljava/lang/Object;
            const-string v2, "hey"
            const/4 v3, 0
            aput-object v2, v1, v3
            invoke-virtual {v0, v1}, Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, LTarget;
            invoke-virtual {v0}, LTarget;->describe()Ljava/lang/String;
            move-result-object v0
            return-object v0
        """) + staticMethod("hostViaReflection()Ljava/lang/Object;", 4, """
            const-class v0, Ljava/lang/System;
            const-string v1, "currentTimeMillis"
            const/4 v2, 0
            new-array v2, v2, [Ljava/lang/Class;
            invoke-virtual {v0, v1, v2}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            move-result-object v0
            const/4 v1, 0
            new-array v2, v1, [Ljava/lang/Object;
            invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """) + staticMethod("hostAllowedViaReflection()Ljava/lang/Object;", 5, """
            const-class v0, Ljava/lang/Math;
            const-string v1, "abs"
            const/4 v2, 1
            new-array v2, v2, [Ljava/lang/Class;
            sget-object v3, Ljava/lang/Integer;->TYPE:Ljava/lang/Class;
            const/4 v4, 0
            aput-object v3, v2, v4
            invoke-virtual {v0, v1, v2}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
            move-result-object v0
            const/4 v1, 1
            new-array v1, v1, [Ljava/lang/Object;
            const/16 v2, -9
            invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v2
            const/4 v3, 0
            aput-object v2, v1, v3
            const/4 v2, 0
            invoke-virtual {v0, v2, v1}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """))

    private val source = TestDex.assemble(target, t)

    @Test
    fun invokeAppMethodReflectively() {
        assertEquals(42, TestDex.run(source, c, "viaMethod()I"))
    }

    @Test
    fun readAndWriteAppStaticField() {
        assertEquals(15, TestDex.run(source, c, "viaField()I"))
    }

    @Test
    fun constructAppObjectReflectively() {
        assertEquals("hey!", TestDex.run(source, c, "viaConstructor()Ljava/lang/String;"))
    }

    @Test
    fun hostMethodInvokeGoesThroughPolicy() {
        val denied = TestDex.run(source, c, "hostViaReflection()Ljava/lang/Object;")
        assertInstanceOf(UnknownVal::class.java, denied)
        val allowed = TestDex.run(source, c, "hostAllowedViaReflection()Ljava/lang/Object;")
        assertEquals(9, allowed)
    }

    @Test
    fun reflectedAppObjectIsDvmObject() {
        val m = staticMethod("obj()Ljava/lang/Object;", 5, """
            const-string v0, "Target"
            invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
            move-result-object v0
            const/4 v1, 1
            new-array v1, v1, [Ljava/lang/Class;
            const-class v2, Ljava/lang/String;
            const/4 v3, 0
            aput-object v2, v1, v3
            invoke-virtual {v0, v1}, Ljava/lang/Class;->getDeclaredConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;
            move-result-object v0
            const/4 v1, 1
            new-array v1, v1, [Ljava/lang/Object;
            const-string v2, "x"
            const/4 v3, 0
            aput-object v2, v1, v3
            invoke-virtual {v0, v1}, Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """)
        val src = TestDex.assemble(target, cls(c, m))
        val obj = TestDex.run(src, c, "obj()Ljava/lang/Object;") as DvmObject
        assertEquals("LTarget;", obj.type)
        assertEquals("x", obj.fields["LTarget;.tag"])
    }
}
