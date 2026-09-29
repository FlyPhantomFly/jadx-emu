package jadx.plugins.emu.host.gui

import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.MenuSelectionManager
import javax.swing.event.ChangeListener

internal class PopupSubmenu(private val title: String) {

    private val names = HashSet<String>()
    private val listener = ChangeListener { regroup() }
    private var installed = false

    @Synchronized
    fun register(name: String) {
        names += name
        if (!installed) {
            MenuSelectionManager.defaultManager().addChangeListener(listener)
            installed = true
        }
    }

    @Synchronized
    fun dispose() {
        if (installed) MenuSelectionManager.defaultManager().removeChangeListener(listener)
        installed = false
        names.clear()
    }

    fun regroup(popup: JPopupMenu): Boolean {
        if (popup.getClientProperty(GROUPED) == true) return false
        val items = popup.components.filter { it is JMenuItem && it !is JMenu && it.text in names }
        if (items.isEmpty()) return false
        val submenu = JMenu(title)
        for (item in items) {
            popup.remove(item)
            submenu.add(item)
        }
        popup.add(submenu)
        popup.putClientProperty(GROUPED, true)
        if (popup.isShowing) popup.pack()
        return true
    }

    private fun regroup() {
        val popup = MenuSelectionManager.defaultManager().selectedPath.firstOrNull() as? JPopupMenu ?: return
        regroup(popup)
    }

    private companion object {
        const val GROUPED = "jadx-emu.grouped"
    }
}
