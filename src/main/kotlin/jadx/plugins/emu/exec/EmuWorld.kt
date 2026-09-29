package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.runtime.DvmObject

/**
 * Mutable emulation state shared between successive [Vm] runs: static fields, Android stubs and hooks.
 *
 * Create one per session and build VMs from it with [worldVm] so that class initialization and
 * static writes persist across calls.
 *
 * @property ctx optional shared engine context providing the host policy, environment and
 *   static-initializer snapshots
 * @property statics static field storage, class descriptor → (field key → value)
 * @property android framework stubs and device environment; defaults to the context's
 * @property hooks method interceptors
 */
class EmuWorld(
    val ctx: EngineContext? = null,
    val statics: HashMap<String, HashMap<String, Any?>> = HashMap(),
    val android: AndroidStubs = ctx?.android ?: AndroidStubs(),
    val hooks: HookRegistry = HookRegistry(),
)

/**
 * Build a concrete-execution [Vm] over [src] that reads and writes the state in [world].
 */
fun worldVm(src: MethodSource, world: EmuWorld, limits: ExecLimits = ExecLimits()): Vm =
    Vm(
        src, host = world.ctx?.host ?: HostBoundary(), limits = limits, hooks = world.hooks, ctx = world.ctx,
        android = world.android, statics = world.statics, androidEnvUnknown = false,
    )

/**
 * Emulate `new classDesc(args)`: allocate a [DvmObject] and run the matching constructor in [world].
 *
 * Framework classes without a body are allocated without running a constructor as long as the
 * constructor signature exists in [FrameworkStubs].
 *
 * @param ctorSig constructor signature such as `(Ljava/lang/String;)V`; defaults to the no-arg constructor
 * @throws IllegalArgumentException if no such constructor exists
 */
fun constructObject(src: MethodSource, world: EmuWorld, classDesc: String, ctorSig: String?, args: List<Any?>): DvmObject {
    val obj = DvmObject(classDesc)
    val sig = ctorSig ?: "()V"
    val init = src.method(classDesc, "<init>$sig")
    if (init != null) {
        worldVm(src, world).invoke(init, args, obj)
        return obj
    }
    if (world.android.isFrameworkClass(classDesc)) {
        if (FrameworkStubs.method(classDesc, "<init>$sig") == null) {
            throw IllegalArgumentException("no <init>$sig on framework class $classDesc")
        }
        return obj
    }
    throw IllegalArgumentException("no <init>$sig on $classDesc")
}
