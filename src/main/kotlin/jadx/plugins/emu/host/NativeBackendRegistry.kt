package jadx.plugins.emu.host

import jadx.plugins.emu.api.NativeBackend

/**
 * Native backends registered by extensions, shared by all of them for one project load.
 *
 * Each registration remembers which extension made it, so it can be dropped when that extension unloads.
 */
internal class NativeBackendRegistry {
    private class Entry(val owner: String, val backend: NativeBackend)

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * @return `false` when the id is already taken; the existing registration is kept.
     */
    @Synchronized
    fun register(owner: String, backend: NativeBackend): Boolean {
        require(backend.id.isNotBlank()) { "native backend id must not be blank" }
        if (backend.id in entries) return false
        entries[backend.id] = Entry(owner, backend)
        return true
    }

    @Synchronized
    fun get(id: String): NativeBackend? = entries[id]?.backend

    @Synchronized
    fun ids(): List<String> = entries.keys.toList()

    /**
     * Drops everything [owner] registered.
     *
     * @return the ids that were removed
     */
    @Synchronized
    fun removeOwner(owner: String): List<String> {
        val gone = entries.filterValues { it.owner == owner }.keys.toList()
        gone.forEach { entries.remove(it) }
        return gone
    }

    @Synchronized
    fun clear() = entries.clear()
}
