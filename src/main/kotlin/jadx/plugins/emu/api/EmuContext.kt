package jadx.plugins.emu.api

import jadx.plugins.emu.exec.EmuWorld
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.model.DexMethod

/**
 * Emulator state for the loaded application, shared by all extensions of a project load.
 *
 * Members are created on first access. Changes made through [world], such as static field values or hooks,
 * are visible to every extension.
 *
 * ```kotlin
 * val method = ctx.emu.method("com.app.Crypto", "key()Ljava/lang/String;") ?: return
 * val value = ctx.emu.newVm().invoke(method)
 * ```
 */
interface EmuContext {

    /**
     * Classes and method bodies of the loaded application.
     *
     * Built from the classes jadx has loaded, in any input format jadx supports. When accessed before jadx
     * has loaded the inputs, for example from [EmuExtension.applies], the input files are parsed directly;
     * this supports only dex and apk files.
     */
    val source: MethodSource

    /**
     * Mutable state shared between VMs created by [newVm]: static fields, Android stubs and hooks.
     */
    val world: EmuWorld

    /**
     * Configuration and cached facts for [source]: host code policy, Android environment and
     * static initializer results.
     */
    val engine: EngineContext

    /**
     * Limits used by [newVm] and [newDataflow] unless overridden.
     */
    val limits: ExecLimits

    /**
     * Creates a VM that executes methods of [source] and reads and writes [world].
     *
     * The VM uses the configured Android device values.
     */
    fun newVm(limits: ExecLimits = this.limits): Vm

    /**
     * Creates a dataflow analysis over a new VM.
     *
     * The VM does not write to [world] and treats Android device values as unknown, so results do not
     * depend on the configured device. To analyze with the device values, use [EngineContext.newVm] with
     * `androidEnvUnknown = false` and pass the VM to [Dataflow].
     */
    fun newDataflow(limits: ExecLimits = this.limits): Dataflow

    /**
     * Returns the method with the given jadx class name and short id, or `null` if [source] has no such method.
     *
     * @param rawClassName Class name as returned by `ClassInfo.getRawName()`, with `$` for inner classes,
     *   for example `com.foo.Bar$Inner`.
     * @param shortId Method short id as returned by `MethodInfo.getShortId()`, for example `dec([BI)Ljava/lang/String;`.
     */
    fun method(rawClassName: String, shortId: String): DexMethod?

    /**
     * Converts a jadx class name to a type descriptor, for example `com.foo.Bar` to `Lcom/foo/Bar;`.
     */
    fun descriptorOf(rawClassName: String): String = "L" + rawClassName.replace('.', '/') + ";"
}
