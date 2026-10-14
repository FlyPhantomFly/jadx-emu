package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineContextTest {
    private val pure = cls("LPure;", """
        .field public static table:[I
        .field public static name:Ljava/lang/String;
        .method static constructor <clinit>()V
            .registers 3
            const/4 v0, 3
            new-array v0, v0, [I
            const/4 v1, 1
            const/16 v2, 10
            aput v2, v0, v1
            sput-object v0, LPure;->table:[I
            const-string v0, "pure"
            sput-object v0, LPure;->name:Ljava/lang/String;
            return-void
        .end method
    """ + staticMethod("bump()V", 2, """
            sget v0, LMutable;->hits:I
            add-int/lit8 v0, v0, 1
            sput v0, LMutable;->hits:I
            return-void
        """))

    private val mutable = cls("LMutable;", """
        .field public static hits:I
        .field public static fromInit:I
        .field public inst:I
        .method static constructor <clinit>()V
            .registers 1
            const/4 v0, 7
            sput v0, LMutable;->fromInit:I
            return-void
        .end method
        .method public constructor <init>()V
            .registers 2
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            const/4 v0, 1
            iput v0, p0, LMutable;->inst:I
            return-void
        .end method
        .method public poke()V
            .registers 2
            const/4 v0, 2
            iput v0, p0, LMutable;->inst:I
            return-void
        .end method
    """)

    private val leaky = cls("LLeaky;", """
        .field public static own:I
        .method static constructor <clinit>()V
            .registers 1
            const/4 v0, 1
            sput v0, LLeaky;->own:I
            const/16 v0, 99
            sput v0, LMutable;->hits:I
            return-void
        .end method
    """)

    private val source = TestDex.assemble(pure, mutable, leaky)

    @Test
    fun clinitSnapshotIsCachedAndDeepCopied() {
        val ctx = TestDex.context(source)
        val a = ctx.clinitStaticsFor("LPure;")!!
        val b = ctx.clinitStaticsFor("LPure;")!!
        assertNotSame(a, b)
        assertNotSame(a["LPure;.table"], b["LPure;.table"])
        (a["LPure;.table"] as IntArray)[1] = 0
        assertEquals(10, (b["LPure;.table"] as IntArray)[1])
        assertEquals("pure", b["LPure;.name"])
    }

    @Test
    fun clinitWritingOtherClassIsNotSnapshotted() {
        val ctx = TestDex.context(source)
        assertNull(ctx.clinitStaticsFor("LLeaky;"))
        assertNull(ctx.clinitStaticsFor("LNope;"))
        val vm = ctx.newVm()
        vm.ensureClinit("LLeaky;")
        assertEquals(1, vm.staticsOf("LLeaky;")["LLeaky;.own"])
        assertEquals(99, vm.staticsOf("LMutable;")["LMutable;.hits"])
    }

    @Test
    fun snapshotIsSharedAcrossVmsButWritesAreNot() {
        val ctx = TestDex.context(source)
        val vm1 = ctx.newVm()
        vm1.ensureClinit("LPure;")
        vm1.staticsOf("LPure;")["LPure;.name"] = "changed"
        val vm2 = ctx.newVm()
        vm2.ensureClinit("LPure;")
        assertEquals("pure", vm2.staticsOf("LPure;")["LPure;.name"])
    }

    @Test
    fun mutableFieldsAreThoseWrittenOutsideInitializers() {
        val ctx = TestDex.context(source)
        val mutableFields = ctx.mutableFields
        assertTrue("LMutable;.hits" in mutableFields)
        assertTrue("LMutable;.inst" in mutableFields)
        assertTrue("LMutable;.fromInit" !in mutableFields)
        assertTrue("LPure;.table" !in mutableFields)
        assertTrue("LLeaky;.own" !in mutableFields)
    }
}
