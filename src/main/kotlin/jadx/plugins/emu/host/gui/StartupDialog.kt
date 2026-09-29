package jadx.plugins.emu.host.gui

import jadx.api.plugins.gui.JadxGuiContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

internal object StartupDialog {

    class Result(val dontShowAgain: Boolean)

    fun show(gui: JadxGuiContext, group: EmuSettingsGroup): Result {
        var dontShow = false
        val run = Runnable {
            val dialog = JDialog(gui.mainFrame, "Jadx Emu", true)
            val tabs = JTabbedPane().apply {
                addTab("Extensions", group.buildComponent())
                for (g in group.subGroups) addTab(g.title, JScrollPane(g.buildComponent()))
            }
            val dontShowBox = JCheckBox("Don't show this again")
            val ok = JButton("OK").apply { addActionListener { dialog.dispose() } }
            val south = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
                add(dontShowBox, BorderLayout.WEST)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(ok) }, BorderLayout.EAST)
            }
            dialog.contentPane.add(tabs, BorderLayout.CENTER)
            dialog.contentPane.add(south, BorderLayout.SOUTH)
            dialog.rootPane.defaultButton = ok
            dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            dialog.pack()
            dialog.setLocationRelativeTo(gui.mainFrame)
            dialog.isVisible = true
            dontShow = dontShowBox.isSelected
            group.apply()
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        return Result(dontShow)
    }
}
