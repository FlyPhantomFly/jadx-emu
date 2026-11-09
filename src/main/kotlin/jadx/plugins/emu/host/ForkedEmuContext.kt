package jadx.plugins.emu.host

import jadx.plugins.emu.api.EmuContext
import jadx.plugins.emu.api.Hooks
import jadx.plugins.emu.api.NativeBackend
import jadx.plugins.emu.exec.EmuWorld
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.worldVm
import java.nio.file.Files
import java.nio.file.Path

/**
 * A child [EmuContext] made by [DefaultEmuContext.fork]: its own source, hooks, static fields and world, but the
 * parent's host policy, Android device values and diagnostics. Native backends stay shared, since those live for
 * the whole project load. Code run here does not touch the parent's statics.
 */
internal class ForkedEmuContext(
    override val source: MethodSource,
    parent: EngineContext,
    private val nativeBackends: NativeBackendRegistry,
    hooks: Hooks,
    override val limits: ExecLimits,
) : EmuContext {

    override val engine: EngineContext = EngineContext(source, limits, parent.host, hooks).also {
        it.diagnostics.clear()
        it.diagnostics.addAll(parent.diagnostics)
    }

    override val world: EmuWorld = EmuWorld(ctx = engine)

    override fun nativeBackend(id: String): NativeBackend? = nativeBackends.get(id)
    override val nativeBackendIds: List<String> get() = nativeBackends.ids()

    override fun newVm(limits: ExecLimits): Vm = worldVm(source, world, limits)

    override fun newDataflow(limits: ExecLimits): Dataflow = Dataflow(engine.newVm(limits))

    override fun method(rawClassName: String, shortId: String): DexMethod? = source.method(descriptorOf(rawClassName), shortId)

    override fun loadDex(file: Path): MethodSource = DexInputSource.load(file.toFile())

    override fun loadDex(bytes: ByteArray): MethodSource {
        val tmp = Files.createTempFile("jadx-emu", ".dex")
        return try {
            Files.write(tmp, bytes)
            DexInputSource.load(tmp.toFile())
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun fork(source: MethodSource, hooks: Hooks?, limits: ExecLimits): EmuContext =
        ForkedEmuContext(source, engine, nativeBackends, hooks ?: Hooks(engine.hooks.env), limits)
}
