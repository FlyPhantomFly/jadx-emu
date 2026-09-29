package jadx.plugins.emu.host.gui

import jadx.api.plugins.gui.JadxGuiContext
import org.slf4j.LoggerFactory
import java.util.function.Consumer

internal class ProjectOptions(private val gui: JadxGuiContext) {

    private val project: Any? by lazy {
        runCatching { gui.mainFrame.javaClass.getMethod("getProject").invoke(gui.mainFrame) }
            .onFailure { LOG.warn("jadx-emu: cannot access jadx project, per-project settings will not persist", it) }
            .getOrNull()
    }

    val available: Boolean get() = project != null

    fun read(key: String): String? {
        val p = project ?: return null
        return runCatching { p.javaClass.getMethod("getPluginOption", String::class.java).invoke(p, key) as? String }.getOrNull()
    }

    fun write(key: String, value: String?): Boolean {
        val p = project ?: return false
        return runCatching {
            val m = p.javaClass.getMethod("updatePluginOptions", Consumer::class.java)
            m.invoke(p, Consumer<MutableMap<String, String>> { map -> if (value == null) map.remove(key) else map[key] = value })
            true
        }.onFailure { LOG.warn("jadx-emu: failed to write project option {}", key, it) }.getOrDefault(false)
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(ProjectOptions::class.java)
    }
}
