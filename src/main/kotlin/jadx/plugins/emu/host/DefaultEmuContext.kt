package jadx.plugins.emu.host

import jadx.api.plugins.JadxPluginContext
import jadx.plugins.emu.api.EmuContext
import jadx.plugins.emu.exec.EmuWorld
import jadx.plugins.emu.exec.EngineContext
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.graph.Dataflow
import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.worldVm
import org.slf4j.LoggerFactory

internal class DefaultEmuContext(
    private val host: JadxPluginContext,
    private val options: EmuOptions,
) : EmuContext {

    override val limits: ExecLimits = options.limits

    override val source: MethodSource by lazy { load() }

    override val engine: EngineContext by lazy { EngineContext(source, limits, options.hostBoundary(), options.androidStubs()) }

    override val world: EmuWorld by lazy { EmuWorld(ctx = engine) }

    override fun newVm(limits: ExecLimits): Vm = worldVm(source, world, limits)

    override fun newDataflow(limits: ExecLimits): Dataflow = Dataflow(engine.newVm(limits))

    override fun method(rawClassName: String, shortId: String): DexMethod? = source.method(descriptorOf(rawClassName), shortId)

    private fun load(): MethodSource {
        val start = System.currentTimeMillis()
        val root = runCatching { host.decompiler.root }.getOrNull()
        val src = if (root != null) {
            val classes = root.classes.mapNotNull { it.clsData }
            LOG.debug("jadx-emu: building emulator source from {} classes loaded by jadx", classes.size)
            DexInputSource.fromClasses(classes)
        } else {
            val files = host.args.inputFiles
            LOG.debug("jadx-emu: jadx has not loaded inputs yet, parsing {} input file(s) directly", files.size)
            DexInputSource.load(files)
        }
        LOG.info("jadx-emu: emulator source ready, {} methods in {}ms", src.allMethods().size, System.currentTimeMillis() - start)
        return src
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(DefaultEmuContext::class.java)
    }
}
