package jadx.plugins.emu

import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.JadxPluginInfo
import jadx.api.plugins.JadxPluginInfoBuilder
import org.slf4j.LoggerFactory

class JadxEmuPlugin : JadxPlugin {

    override fun getPluginInfo(): JadxPluginInfo =
        JadxPluginInfoBuilder.pluginId(PLUGIN_ID)
            .name("Jadx Emu")
            .description("Dalvik emulation engine for jadx")
            .homepage("https://github.com/nitanmarcel/jadx-emu")
            .requiredJadxVersion("1.5.6, r2678")
            .build()

    override fun init(context: JadxPluginContext) {
        LOG.info("jadx-emu plugin loaded")
    }

    companion object {
        const val PLUGIN_ID = "jadx-emu"
        private val LOG = LoggerFactory.getLogger(JadxEmuPlugin::class.java)
    }
}
