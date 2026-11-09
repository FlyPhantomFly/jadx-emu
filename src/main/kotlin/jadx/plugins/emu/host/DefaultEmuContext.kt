package jadx.plugins.emu.host

import jadx.api.plugins.JadxPluginContext
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
import org.slf4j.LoggerFactory

internal class DefaultEmuContext(
    private val jadx: JadxPluginContext,
    private val options: EmuOptions,
    private val nativeBackends: NativeBackendRegistry,
) : EmuContext {

    override val limits: ExecLimits = options.limits

    override val source: MethodSource by lazy { load() }

    override val engine: EngineContext by lazy { EngineContext(source, limits, options.hostBoundary(), options.hooks()) }

    override val world: EmuWorld by lazy { EmuWorld(ctx = engine) }

    override fun nativeBackend(id: String): NativeBackend? = nativeBackends.get(id)
    override val nativeBackendIds: List<String> get() = nativeBackends.ids()

    override fun newVm(limits: ExecLimits): Vm = worldVm(source, world, limits)

    override fun newDataflow(limits: ExecLimits): Dataflow = Dataflow(engine.newVm(limits))

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

    override fun method(rawClassName: String, shortId: String): DexMethod? = source.method(descriptorOf(rawClassName), shortId)

    private fun load(): MethodSource {
        val start = System.currentTimeMillis()
        val root = runCatching { jadx.decompiler.root }.getOrNull()
        val src = if (root != null) {
            val classes = root.classes.mapNotNull { it.clsData }
            LOG.debug("jadx-emu: building emulator source from {} classes loaded by jadx", classes.size)
            DexInputSource.fromClasses(classes)
        } else {
            val files = jadx.args.inputFiles
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
