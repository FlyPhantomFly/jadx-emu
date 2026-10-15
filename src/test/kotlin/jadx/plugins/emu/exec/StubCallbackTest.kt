package jadx.plugins.emu.exec

import jadx.plugins.emu.TestDex
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.TestDex.cls
import jadx.plugins.emu.TestDex.staticMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StubCallbackTest {
    private val c = "LT;"

    private val task = """
        .class public LTask;
        .super Ljava/lang/Object;
        .implements Ljava/lang/Runnable;
        .field public final key:I
        .method public constructor <init>(I)V
            .registers 2
            invoke-direct {p0}, Ljava/lang/Object;-><init>()V
            iput p1, p0, LTask;->key:I
            return-void
        .end method
        .method public run()V
            .registers 4
            const-string v0, "jmoon"
            iget v1, p0, LTask;->key:I
            invoke-static {v0, v1}, LT;->dec(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v0
            sput-object v0, LT;->secret:Ljava/lang/String;
            return-void
        .end method
    """.trimIndent()

    private val t = cls(c, """
        .field public static secret:Ljava/lang/String;
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
        """) + staticMethod("load()Ljava/lang/String;", 3, """
            invoke-static {}, Ljava/util/concurrent/Executors;->newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;
            move-result-object v0
            const/4 v1, 3
            new-instance v2, LTask;
            invoke-direct {v2, v1}, LTask;-><init>(I)V
            invoke-interface {v0, v2}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V
            sget-object v0, LT;->secret:Ljava/lang/String;
            return-object v0
        """))

    private val source = TestDex.assemble(task, t)
    private val load get() = TestDex.method(source, c, "load()Ljava/lang/String;")

    private fun world(hooks: Hooks = Hooks()): EmuWorld = EmuWorld(ctx = EngineContext(source, hooks = hooks))

    private fun executor(hooks: Hooks) {
        hooks.stub("Ljava/util/concurrent/Executors;->newSingleThreadExecutor") { replace(DvmObject("Ljava/util/concurrent/ExecutorService;")) }
    }

    @Test
    fun deniedExecutorRecoversNothing() {
        assertNull(worldVm(source, world()).invoke(load))
    }

    @Test
    fun valueOnlyStubsRecoverNothing() {
        val hooks = Hooks()
        executor(hooks)
        hooks.stub("Ljava/util/concurrent/ExecutorService;->execute", null)
        assertNull(worldVm(source, world(hooks)).invoke(load))
    }

    @Test
    fun stubRunningTheCallbackOnTheCallingVmRecoversTheSecret() {
        val hooks = Hooks()
        executor(hooks)
        val seen = ArrayList<String>()
        hooks.stub("Ljava/util/concurrent/ExecutorService;->execute") {
            seen.add((receiver as DvmObject).type + " " + (args[0] as DvmObject).type)
            vm.invoke(args[0]!!, "run()V")
            replace(null)
        }
        assertEquals("inllm", worldVm(source, world(hooks)).invoke(load))
        assertEquals(listOf("Ljava/util/concurrent/ExecutorService; LTask;"), seen)
    }

    @Test
    fun stubRunningTheCallbackOnAnotherWorldVmRecoversTheSecret() {
        val hooks = Hooks()
        val w = world(hooks)
        executor(hooks)
        hooks.stub("Ljava/util/concurrent/ExecutorService;->execute") {
            val r = args[0] as DvmObject
            worldVm(source, w).call(TestDex.method(source, r.type, "run()V"), emptyList(), r)
            replace(null)
        }
        assertEquals("inllm", worldVm(source, w).invoke(load))
    }

    @Test
    fun stubRunningTheCallbackOnAnUnrelatedVmLosesTheWrite() {
        val hooks = Hooks()
        executor(hooks)
        hooks.stub("Ljava/util/concurrent/ExecutorService;->execute") {
            val r = args[0] as DvmObject
            EngineContext(source, hooks = hooks).newVm().call(TestDex.method(source, r.type, "run()V"), emptyList(), r)
            replace(null)
        }
        assertNull(worldVm(source, world(hooks)).invoke(load))
    }

    @Test
    fun hookCanRunEmulatedCodeToo() {
        val hooks = Hooks()
        executor(hooks)
        hooks.hook("Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V") {
            vm.invoke(args[0]!!, "run()V")
            replace(null)
        }
        assertEquals("inllm", worldVm(source, world(hooks)).invoke(load))
    }
}
