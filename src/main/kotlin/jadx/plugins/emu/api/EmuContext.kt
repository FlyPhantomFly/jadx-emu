package jadx.plugins.emu.api

import jadx.plugins.emu.exec.EmuWorld
import jadx.plugins.emu.exec.DiagnosticSink
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.model.DexMethod
import java.nio.file.Path

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
     * Mutable state shared between VMs created by [newVm]: static fields, hooks and stubs.
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
     * The host code policy shared by every VM of this emulator. The same as [engine]'s `host`.
     */
    val host: HostBoundary
        get() = engine.host

    /**
     * Receivers of what the engine could not do, such as a refused host call or a missing stub. Add a sink to see
     * them. The same as [engine]'s `diagnostics`.
     */
    val diagnostics: MutableList<DiagnosticSink>
        get() = engine.diagnostics

    /**
     * Returns the method with the given descriptor, for example `Lcom/foo/Bar;->dec([BI)Ljava/lang/String;`, or
     * `null` if [source] has no such method or [descriptor] is not of that form.
     */
    fun method(descriptor: String): DexMethod? {
        val i = descriptor.indexOf("->")
        if (i <= 0) return null
        return source.method(descriptor.substring(0, i), descriptor.substring(i + 2))
    }

    /**
     * Returns the method a code view [target] points at, or `null` for a class or field target, or when [source]
     * has no such method.
     */
    fun method(target: EmuTarget): DexMethod? {
        val shortId = target.shortId ?: return null
        return source.method(descriptorOf(target.rawClassName), shortId)
    }

    /**
     * The native backend that an extension registered under [id], or `null` when there is none.
     *
     * Look it up when you need it, not in [EmuExtension.init]: extensions are initialized in the order of their jar names, so the one that provides the backend may not have run yet.
     */
    fun nativeBackend(id: String): NativeBackend? = null

    /**
     * The ids of the native backends registered so far, in registration order.
     */
    val nativeBackendIds: List<String>
        get() = emptyList()

    /**
     * Converts a jadx class name to a type descriptor, for example `com.foo.Bar` to `Lcom/foo/Bar;`.
     */
    fun descriptorOf(rawClassName: String): String = "L" + rawClassName.replace('.', '/') + ";"

    /**
     * Loads a dex, apk or jar file as a method source, for example to emulate code recovered at runtime.
     *
     * Layer it onto the app with [jadx.plugins.emu.exec.plus]: `emu.source + emu.loadDex(file)`.
     */
    fun loadDex(file: Path): MethodSource

    /**
     * Loads a dex from memory, for example a hidden dex an extension just decrypted.
     */
    fun loadDex(bytes: ByteArray): MethodSource

    /**
     * An emulator with the same host policy, Android device values and diagnostics, but its own static fields.
     *
     * Use it to run recovered code without disturbing the main emulator's state.
     *
     * @param source code to execute, for example `emu.source + emu.loadDex(bytes)`
     * @param hooks hooks and stubs to use; `null` for a fresh set with the same Android environment
     * @param limits execution limits for the child
     */
    fun fork(source: MethodSource = this.source, hooks: Hooks? = null, limits: ExecLimits = this.limits): EmuContext
}
