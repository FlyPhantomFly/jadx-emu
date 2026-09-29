package jadx.plugins.emu.host

import jadx.api.plugins.options.JadxPluginOptions
import jadx.api.plugins.options.OptionDescription
import jadx.api.plugins.options.OptionFlag
import jadx.api.plugins.options.OptionType
import jadx.plugins.emu.JadxEmuPlugin.Companion.PLUGIN_ID
import java.util.EnumSet

internal class CompositeOptions(val host: EmuOptions) : JadxPluginOptions {

    private class Group(val prefix: String, val delegate: JadxPluginOptions, val perProject: Boolean)

    private val groups = ArrayList<Group>()
    private var current: Map<String, String> = emptyMap()

    override fun setOptions(options: Map<String, String>) {
        current = HashMap(options)
        host.setOptions(options)
        for (g in groups) apply(g)
    }

    override fun getOptionsDescriptions(): List<OptionDescription> {
        val out = ArrayList<OptionDescription>(host.optionsDescriptions)
        for (g in groups) g.delegate.optionsDescriptions.mapTo(out) { Prefixed(g.prefix, it, g.perProject) }
        return out
    }

    fun hostDescriptions(): List<OptionDescription> = host.optionsDescriptions

    fun descriptionsFor(extensionId: String): List<OptionDescription> {
        val prefix = "$PLUGIN_ID.$extensionId."
        return groups.filter { it.prefix == prefix }.flatMap { g -> g.delegate.optionsDescriptions.map { Prefixed(g.prefix, it, g.perProject) } }
    }

    fun add(extensionId: String, options: JadxPluginOptions, perProject: Boolean) {
        val g = Group("$PLUGIN_ID.$extensionId.", options, perProject)
        groups += g
        apply(g)
    }

    fun clearExtensions() {
        groups.clear()
    }

    fun valuesHash(live: Map<String, String>): String =
        live.entries.filter { it.key.startsWith("$PLUGIN_ID.") }.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun apply(g: Group) {
        val sub = HashMap<String, String>()
        for ((k, v) in current) if (k.startsWith(g.prefix)) sub[k.removePrefix(g.prefix)] = v
        g.delegate.setOptions(sub)
    }

    private class Prefixed(
        private val prefix: String,
        private val d: OptionDescription,
        private val perProject: Boolean,
    ) : OptionDescription {
        override fun name(): String = prefix + d.name()
        override fun description(): String = d.description()
        override fun values(): List<String> = d.values()
        override fun defaultValue(): String? = d.defaultValue()
        override fun getType(): OptionType = d.type
        override fun getFlags(): Set<OptionFlag> {
            if (!perProject) return d.flags
            val f = EnumSet.noneOf(OptionFlag::class.java)
            f.addAll(d.flags)
            f.add(OptionFlag.PER_PROJECT)
            return f
        }
    }
}
