package jadx.plugins.emu.host.gui

import jadx.api.plugins.JadxPluginContext
import jadx.api.plugins.events.types.ReloadProject
import jadx.api.plugins.gui.ISettingsGroup
import jadx.api.plugins.gui.JadxGuiContext
import jadx.plugins.emu.api.ExtensionMode
import jadx.plugins.emu.host.CompositeOptions
import jadx.plugins.emu.host.EmuOptions
import jadx.plugins.emu.host.ExtensionLoader
import jadx.plugins.emu.host.ExtensionStatus
import jadx.plugins.emu.host.LoadedExtension
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.AbstractTableModel
import kotlin.io.path.name

internal class EmuSettingsGroup(
    private val host: JadxPluginContext,
    private val gui: JadxGuiContext,
    private val loader: ExtensionLoader,
    private val options: CompositeOptions,
    private val onReload: () -> Unit = {},
) : ISettingsGroup {

    val projectOptions = ProjectOptions(gui)
    private val enabled: MutableSet<String> = LinkedHashSet(currentEnabled())
    private var togglesChanged = false

    private val groups: List<ISettingsGroup> by lazy {
        val out = ArrayList<ISettingsGroup>()
        val all = options.hostDescriptions()
        out += gui.settings().buildSettingsGroupForOptions("General", all.filter { !it.name().startsWith(EmuOptions.HOST_PREFIX) && !it.name().startsWith(EmuOptions.ANDROID_PREFIX) })
        out += HostCodeGroup(host, gui, all.filter { it.name().startsWith(EmuOptions.HOST_PREFIX) })
        out += gui.settings().buildSettingsGroupForOptions("Android device", all.filter { it.name().startsWith(EmuOptions.ANDROID_PREFIX) })
        for (ext in loader.extensions) {
            val descs = options.descriptionsFor(ext.info.id)
            if (descs.isNotEmpty()) out += gui.settings().buildSettingsGroupForOptions(ext.info.name, descs)
        }
        out
    }

    override fun getTitle(): String = "Jadx Emu"

    override fun getSubGroups(): List<ISettingsGroup> = groups

    override fun buildComponent(): JComponent {
        val model = Model()
        val table = JTable(model).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            autoCreateRowSorter = true
            columnModel.getColumn(0).apply { maxWidth = 70; preferredWidth = 70 }
            columnModel.getColumn(3).apply { maxWidth = 100; preferredWidth = 90 }
            columnModel.getColumn(4).apply { maxWidth = 120; preferredWidth = 110 }
            fillsViewportHeight = true
            preferredScrollableViewportSize = Dimension(PANEL_WIDTH - 20, 160)
        }

        val install = JButton("Install from jar…").apply { addActionListener { installJar() } }
        val remove = JButton("Remove").apply {
            isEnabled = false
            addActionListener {
                val row = table.selectedRow.takeIf { it >= 0 }?.let(table::convertRowIndexToModel) ?: return@addActionListener
                removeExtension(loader.extensions[row], model)
            }
        }
        table.selectionModel.addListSelectionListener { remove.isEnabled = table.selectedRow >= 0 }
        val openDir = JButton("Open folder").apply { addActionListener { openFolder() } }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(install); add(remove); add(openDir) }
        return JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = Dimension(PANEL_WIDTH, 260)
        }
    }

    override fun close(save: Boolean) {
        if (save && togglesChanged) reload()
    }

    fun apply() {
        if (togglesChanged) {
            writeEnabled()
            togglesChanged = false
        }
    }

    private fun reload() {
        apply()
        host.events().send(ReloadProject.EVENT)
        onReload()
    }

    private fun currentEnabled(): Set<String> = EmuOptions.splitList(enabledValue(projectOptions, host.args.pluginOptions)).toSet()

    private fun writeEnabled() {
        val value = enabled.sorted().joinToString(",")
        if (!projectOptions.write(EmuOptions.ENABLED_EXTENSIONS_OPT, value)) {
            warn("The setting couldn't be saved to the project. Set the ${EmuOptions.ENABLED_EXTENSIONS_OPT} option instead.")
        }
    }

    private fun installJar() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Install jadx-emu extension"
            fileFilter = FileNameExtensionFilter("Extension jar", "jar")
            isMultiSelectionEnabled = true
        }
        if (chooser.showOpenDialog(gui.mainFrame) != JFileChooser.APPROVE_OPTION) return
        var installed = 0
        for (f in chooser.selectedFiles) {
            runCatching {
                Files.createDirectories(loader.directory)
                Files.copy(f.toPath(), loader.directory.resolve(f.name), StandardCopyOption.REPLACE_EXISTING)
                LOG.info("jadx-emu: installed extension {}", f.name)
                installed++
            }.onFailure {
                LOG.error("jadx-emu: install failed for {}", f, it)
                warn("The extension couldn't be installed: ${it.message}")
            }
        }
        if (installed > 0) reload()
    }

    private fun removeExtension(ext: LoadedExtension, model: Model) {
        val choices = arrayOf("Remove", "Cancel")
        val answer = JOptionPane.showOptionDialog(
            gui.mainFrame, "Remove \"${ext.info.name}\"?", "Jadx Emu",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[1],
        )
        if (answer != 0) return
        loader.remove(ext)
        model.fireTableDataChanged()
        reload()
    }

    private fun openFolder() {
        runCatching {
            Files.createDirectories(loader.directory)
            Desktop.getDesktop().open(loader.directory.toFile())
        }.onFailure { LOG.warn("jadx-emu: cannot open {}", loader.directory, it) }
    }

    private fun warn(message: String) {
        LOG.warn("jadx-emu: {}", message)
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(gui.mainFrame, message, "Jadx Emu", JOptionPane.WARNING_MESSAGE)
        }
    }

    private inner class Model : AbstractTableModel() {
        private val columns = arrayOf("Enabled", "Name", "ID", "Mode", "Status", "File")

        override fun getRowCount(): Int = loader.extensions.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
            columnIndex == 0 && loader.extensions[rowIndex].info.mode == ExtensionMode.AUTO

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val ext = loader.extensions[rowIndex]
            return when (columnIndex) {
                0 -> ext.info.mode != ExtensionMode.AUTO || ext.info.id in enabled
                1 -> ext.info.name
                2 -> ext.info.id
                3 -> if (ext.info.mode == ExtensionMode.AUTO) "Automatic" else "On demand"
                4 -> statusText(ext)
                else -> ext.origin.name
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            val id = loader.extensions[rowIndex].info.id
            if (aValue == true) enabled.add(id) else enabled.remove(id)
            togglesChanged = true
            fireTableRowsUpdated(rowIndex, rowIndex)
        }

        private fun statusText(ext: LoadedExtension): String = when (ext.status) {
            ExtensionStatus.ACTIVE -> if (ext.info.mode == ExtensionMode.ON_DEMAND) "Available" else "Loaded"
            ExtensionStatus.DISABLED -> "Not enabled"
            ExtensionStatus.NOT_APPLICABLE -> "Not applicable"
            ExtensionStatus.INCOMPATIBLE -> "Requires jadx-emu ${ext.info.requiredEmuVersion}"
            ExtensionStatus.FAILED -> "Failed: ${ext.error?.message ?: ext.error?.javaClass?.simpleName}"
            ExtensionStatus.REMOVED -> "Removed"
        }
    }

    companion object {
        private const val PANEL_WIDTH = 640
        private val LOG = LoggerFactory.getLogger(EmuSettingsGroup::class.java)

        fun enabledValue(project: ProjectOptions, args: Map<String, String>): String =
            if (project.available) project.read(EmuOptions.ENABLED_EXTENSIONS_OPT) ?: ""
            else args[EmuOptions.ENABLED_EXTENSIONS_OPT] ?: ""
    }
}
