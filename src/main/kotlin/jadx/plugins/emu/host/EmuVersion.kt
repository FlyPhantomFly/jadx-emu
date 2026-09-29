package jadx.plugins.emu.host

import java.util.Properties

internal object EmuVersion {

    val current: String by lazy {
        EmuVersion::class.java.getResourceAsStream("/jadx-emu.properties")?.use { s ->
            Properties().apply { load(s) }.getProperty("version")
        } ?: "dev"
    }

    val isDev: Boolean get() = parse(current) == null

    fun isCompatible(required: String?): Boolean {
        if (required == null || isDev) return true
        val req = parse(required) ?: return false
        val cur = parse(current) ?: return true
        for (i in 0 until 3) {
            if (cur[i] != req[i]) return cur[i] > req[i]
        }
        return true
    }

    private fun parse(v: String): IntArray? {
        val parts = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.')
        if (parts.size !in 1..3) return null
        val out = IntArray(3)
        for (i in parts.indices) out[i] = parts[i].toIntOrNull() ?: return null
        return out
    }
}
