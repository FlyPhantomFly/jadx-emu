package jadx.plugins.emu.host.gui

import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.gui.ISettingsGroup
import jadx.api.plugins.gui.JadxGuiContext
import jadx.api.plugins.options.OptionDescription
import jadx.plugins.emu.host.EmuOptions
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class HostCodeGroup(
    private val host: JadxPluginContext,
    private val gui: JadxGuiContext,
    private val descriptions: List<OptionDescription>,
) : ISettingsGroup {

    override fun getTitle(): String = "Host code"

    override fun buildComponent(): JComponent {
        val simple = descriptions.filter { it.name() !in LIST_OPTIONS }
        val top = gui.settings().buildSettingsGroupForOptions(title, simple).buildComponent()

        val lists = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
            add(listEditor("Additional denied signatures, one per line, e.g. java.util.Scanner  or  java.lang.String#trim()", EmuOptions.HOST_BLOCK_OPT))
            add(Box.createVerticalStrut(10))
            add(listEditor("Re-allowed signatures, one per line, e.g. java.nio.file.Files#readAllBytes(java.nio.file.Path)", EmuOptions.HOST_ALLOW_OPT))
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(lists, BorderLayout.CENTER)
        }
    }

    private fun listEditor(label: String, key: String): JComponent {
        val area = JTextArea(EmuOptions.splitList(host.args.pluginOptions[key] ?: "").joinToString("\n"), 5, 40).apply {
            lineWrap = false
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = store()
                override fun removeUpdate(e: DocumentEvent) = store()
                override fun changedUpdate(e: DocumentEvent) = store()
                private fun store() {
                    host.args.pluginOptions[key] = EmuOptions.splitList(text).joinToString("\n")
                }
            })
        }
        return JPanel(BorderLayout(0, 4)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JLabel(label), BorderLayout.NORTH)
            add(JScrollPane(area).apply { preferredSize = Dimension(560, 110) }, BorderLayout.CENTER)
        }
    }

    private companion object {
        val LIST_OPTIONS = setOf(EmuOptions.HOST_ALLOW_OPT, EmuOptions.HOST_BLOCK_OPT)
    }
}
