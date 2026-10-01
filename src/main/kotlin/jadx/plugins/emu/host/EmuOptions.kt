package jadx.plugins.emu.host

import jadx.api.plugins.options.OptionFlag
import jadx.api.plugins.options.impl.BasePluginOptionsBuilder
import jadx.plugins.emu.JadxEmuPlugin.Companion.PLUGIN_ID
import jadx.plugins.emu.exec.AndroidEnv
import jadx.plugins.emu.exec.AndroidStubs
import jadx.plugins.emu.exec.ExecLimits
import jadx.plugins.emu.exec.HostBoundary
import jadx.plugins.emu.exec.policy.SignatureSet
import org.slf4j.LoggerFactory

internal class EmuOptions : BasePluginOptionsBuilder() {

    var enabled: Boolean = true
        private set
    var startupDialog: Boolean = true
        private set
    var enabledExtensions: Set<String> = emptySet()
        private set
    var targets: String = ""
        private set

    private var maxSteps = 500_000
    private var maxMillis = 2_000L
    private var maxDepth = 64

    private var hostExecute = true
    private var hostAllow = ""
    private var hostBlock = ""
    private var restrictRandom = true
    private var restrictTime = true
    private var restrictEnv = true

    private var sdk = 33
    private var release = "13"
    private var model = "Pixel 6"
    private var manufacturer = "Google"
    private var brand = "google"
    private var device = "oriole"
    private var product = "oriole"
    private var fingerprint = "google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys"
    private var caller = ""

    val limits: ExecLimits get() = ExecLimits(maxSteps = maxSteps, maxMillis = maxMillis, maxDepth = maxDepth)

    fun hostBoundary(): HostBoundary =
        if (!hostExecute) HostBoundary.disabled()
        else HostBoundary(
            deny = parsePolicy(HOST_BLOCK_OPT, hostBlock), allow = parsePolicy(HOST_ALLOW_OPT, hostAllow),
            restrictRandom = restrictRandom, restrictTime = restrictTime, restrictEnv = restrictEnv,
        )

    private fun parsePolicy(opt: String, text: String): SignatureSet =
        runCatching { HostBoundary.parse(text) }
            .onFailure { LOG.warn("jadx-emu: ignoring {}: {}", opt, it.message) }
            .getOrDefault(SignatureSet.EMPTY)

    fun androidStubs(): AndroidStubs =
        AndroidStubs(AndroidEnv(sdk, release, model, manufacturer, brand, device, product, fingerprint)).apply {
            syntheticCaller = caller.ifBlank { null }
        }

    override fun registerOptions() {
        boolOption(ENABLED_OPT).description("Enable jadx-emu").defaultValue(true).setter { enabled = it }
        boolOption(STARTUP_DIALOG_OPT).description("Show settings when a project opens").defaultValue(true)
            .flags(OptionFlag.NOT_CHANGING_CODE).setter { startupDialog = it }

        strOption(ENABLED_EXTENSIONS_OPT).description("Enabled extensions (comma-separated IDs)").defaultValue("")
            .flags(OptionFlag.PER_PROJECT, OptionFlag.HIDE_IN_GUI).setter { enabledExtensions = csv(it) }
        strOption(TARGETS_OPT).description("On-demand targets (id:class->method, …)").defaultValue("")
            .flags(OptionFlag.PER_PROJECT, OptionFlag.HIDE_IN_GUI).setter { targets = it }

        intOption(MAX_STEPS_OPT).description("Maximum instructions per method").defaultValue(500_000).setter { maxSteps = it }
        intOption(MAX_MILLIS_OPT).description("Maximum time per method (ms)").defaultValue(2_000).setter { maxMillis = it.toLong() }
        intOption(MAX_DEPTH_OPT).description("Maximum call depth").defaultValue(64).setter { maxDepth = it }

        boolOption(HOST_EXECUTE_OPT).description("Execute JDK classes").defaultValue(true).setter { hostExecute = it }
        boolOption(HOST_RESTRICT_RANDOM_OPT).description("Deny random-number APIs").defaultValue(true).setter { restrictRandom = it }
        boolOption(HOST_RESTRICT_TIME_OPT).description("Deny current-time and time-zone APIs").defaultValue(true).setter { restrictTime = it }
        boolOption(HOST_RESTRICT_ENV_OPT).description("Deny host-environment APIs (properties, env, locale)").defaultValue(true)
            .setter { restrictEnv = it }
        strOption(HOST_ALLOW_OPT).description("Re-allowed signatures, forbidden-apis syntax, one per line (advanced)").defaultValue("")
            .setter { hostAllow = splitList(it).joinToString("\n") }
        strOption(HOST_BLOCK_OPT).description("Additional denied signatures, forbidden-apis syntax, one per line (advanced)").defaultValue("")
            .setter { hostBlock = splitList(it).joinToString("\n") }

        intOption(ANDROID_SDK_OPT).description("SDK version").defaultValue(33).setter { sdk = it }
        strOption(ANDROID_RELEASE_OPT).description("Android version").defaultValue("13").setter { release = it }
        strOption(ANDROID_MODEL_OPT).description("Model").defaultValue("Pixel 6").setter { model = it }
        strOption(ANDROID_MANUFACTURER_OPT).description("Manufacturer").defaultValue("Google").setter { manufacturer = it }
        strOption(ANDROID_BRAND_OPT).description("Brand").defaultValue("google").setter { brand = it }
        strOption(ANDROID_DEVICE_OPT).description("Device").defaultValue("oriole").setter { device = it }
        strOption(ANDROID_PRODUCT_OPT).description("Product").defaultValue("oriole").setter { product = it }
        strOption(ANDROID_FINGERPRINT_OPT).description("Fingerprint")
            .defaultValue("google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys").setter { fingerprint = it }
        strOption(ANDROID_CALLER_OPT).description("Caller class in stack traces (advanced)").defaultValue("").setter { caller = it }
    }

    private fun csv(value: String): Set<String> = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    companion object {
        private val LOG = LoggerFactory.getLogger(EmuOptions::class.java)

        fun splitList(value: String): List<String> = value.split('\n').map(String::trim).filter(String::isNotEmpty)

        const val ENABLED_OPT = "$PLUGIN_ID.enabled"
        const val STARTUP_DIALOG_OPT = "$PLUGIN_ID.startup-dialog"
        const val ENABLED_EXTENSIONS_OPT = "$PLUGIN_ID.extensions.enabled"
        const val TARGETS_OPT = "$PLUGIN_ID.targets"
        const val MAX_STEPS_OPT = "$PLUGIN_ID.max-steps"
        const val MAX_MILLIS_OPT = "$PLUGIN_ID.max-millis"
        const val MAX_DEPTH_OPT = "$PLUGIN_ID.max-depth"
        const val HOST_PREFIX = "$PLUGIN_ID.host."
        const val HOST_EXECUTE_OPT = "${HOST_PREFIX}execute"
        const val HOST_ALLOW_OPT = "${HOST_PREFIX}allow"
        const val HOST_BLOCK_OPT = "${HOST_PREFIX}block"
        const val HOST_RESTRICT_RANDOM_OPT = "${HOST_PREFIX}restrict-random"
        const val HOST_RESTRICT_TIME_OPT = "${HOST_PREFIX}restrict-time"
        const val HOST_RESTRICT_ENV_OPT = "${HOST_PREFIX}restrict-env"
        const val ANDROID_PREFIX = "$PLUGIN_ID.android."
        const val ANDROID_SDK_OPT = "${ANDROID_PREFIX}sdk"
        const val ANDROID_RELEASE_OPT = "${ANDROID_PREFIX}release"
        const val ANDROID_MODEL_OPT = "${ANDROID_PREFIX}model"
        const val ANDROID_MANUFACTURER_OPT = "${ANDROID_PREFIX}manufacturer"
        const val ANDROID_BRAND_OPT = "${ANDROID_PREFIX}brand"
        const val ANDROID_DEVICE_OPT = "${ANDROID_PREFIX}device"
        const val ANDROID_PRODUCT_OPT = "${ANDROID_PREFIX}product"
        const val ANDROID_FINGERPRINT_OPT = "${ANDROID_PREFIX}fingerprint"
        const val ANDROID_CALLER_OPT = "${ANDROID_PREFIX}caller"
    }
}
