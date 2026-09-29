package jadx.plugins.emu.host

import jadx.plugins.emu.api.EmuTarget
import jadx.plugins.emu.api.TargetStore
import jadx.plugins.emu.host.gui.ProjectOptions
import org.slf4j.LoggerFactory

internal class TargetRegistry(private val project: ProjectOptions?, initial: String) {

    private val byExtension = LinkedHashMap<String, LinkedHashSet<EmuTarget>>()
    private val touched = LinkedHashSet<String>()

    init {
        for (entry in EmuOptions.splitList(initial)) {
            val i = entry.indexOf(':')
            if (i <= 0) {
                LOG.warn("jadx-emu: ignoring target entry without extension id: '{}'", entry)
                continue
            }
            byExtension.getOrPut(entry.substring(0, i).trim()) { LinkedHashSet() } += EmuTarget.parse(entry.substring(i + 1))
        }
    }

    fun forExtension(id: String): TargetStore = View(id)

    @Synchronized
    fun serialize(): String =
        byExtension.entries.flatMap { (id, targets) -> targets.map { "$id:${it.key}" } }.joinToString(",")

    @Synchronized
    fun touchedClasses(): Set<String> = LinkedHashSet(touched)

    @Synchronized
    private fun set(id: String): LinkedHashSet<EmuTarget> = byExtension.getOrPut(id) { LinkedHashSet() }

    @Synchronized
    private fun save(changed: EmuTarget) {
        touched += changed.rawClassName
        project?.write(EmuOptions.TARGETS_OPT, serialize())
    }

    private inner class View(private val id: String) : TargetStore {
        override fun all(): Set<EmuTarget> = synchronized(this@TargetRegistry) { LinkedHashSet(set(id)) }
        override fun contains(target: EmuTarget): Boolean = synchronized(this@TargetRegistry) { target in set(id) }

        override fun add(target: EmuTarget) {
            synchronized(this@TargetRegistry) { if (set(id).add(target)) save(target) }
        }

        override fun remove(target: EmuTarget) {
            synchronized(this@TargetRegistry) { if (set(id).remove(target)) save(target) }
        }

        override fun toggle(target: EmuTarget): Boolean = synchronized(this@TargetRegistry) {
            val s = set(id)
            val present = !s.add(target)
            if (present) s.remove(target)
            save(target)
            !present
        }

        override fun clear() {
            synchronized(this@TargetRegistry) {
                val s = set(id)
                if (s.isEmpty()) return
                val removed = s.toList()
                s.clear()
                removed.forEach(::save)
            }
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(TargetRegistry::class.java)
    }
}
