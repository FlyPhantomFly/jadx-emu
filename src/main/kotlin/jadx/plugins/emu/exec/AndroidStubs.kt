package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.UNKNOWN

/**
 * Sentinel returned by stub and host lookups meaning "no implementation here, try the next layer".
 * Compare by identity.
 */
object NotHandled

/**
 * Thrown when emulated code calls a framework method that has a signature stub but no behaviour.
 *
 * @property descriptor the method, as `Lcls;->shortId`
 */
class StubNotImplemented(val descriptor: String) :
    RuntimeException("no implementation for framework method $descriptor; register one with AndroidStubs.registerMethod")

/**
 * Implementation of a stubbed method; see [AndroidStubs.registerMethod].
 */
fun interface StubHandler {
    /**
     * @param receiver the `this` value, or null for static methods
     * @return the method result, or [NotHandled] to fall through to other handlers
     */
    fun invoke(receiver: Any?, args: List<Any?>): Any?
}

/**
 * Values reported for `android.os.Build` and `Build.VERSION` fields.
 */
class AndroidEnv(
    sdkInt: Int = 33,
    release: String = "13",
    model: String = "Pixel 6",
    manufacturer: String = "Google",
    brand: String = "google",
    device: String = "oriole",
    product: String = "oriole",
    fingerprint: String = "google/oriole/oriole:13/TQ3A.230805.001/10316531:user/release-keys",
) {
    private val fields: Map<String, Any?> = mapOf(
        "Landroid/os/Build\$VERSION;.SDK_INT" to sdkInt,
        "Landroid/os/Build\$VERSION;.RELEASE" to release,
        "Landroid/os/Build;.MODEL" to model,
        "Landroid/os/Build;.MANUFACTURER" to manufacturer,
        "Landroid/os/Build;.BRAND" to brand,
        "Landroid/os/Build;.DEVICE" to device,
        "Landroid/os/Build;.PRODUCT" to product,
        "Landroid/os/Build;.FINGERPRINT" to fingerprint,
        "Landroid/os/Build;.SERIAL" to "unknown",
        "Landroid/os/Build;.HARDWARE" to device,
    )

    /**
     * Value of the `Build` field, or [NotHandled] if this environment does not define it.
     */
    fun field(declClass: String, name: String): Any? {
        val key = "$declClass.$name"
        return if (key in fields) fields[key] else NotHandled
    }
}

/**
 * User-replaceable implementations of Android framework methods and fields.
 *
 * Built-ins cover `android.util.Base64`, `android.util.Log` and stack-trace inspection. Calls into
 * framework classes (`android.*`, `com.android.*`, `dalvik.*`, `libcore.*`) that have no handler raise
 * [StubNotImplemented]; non-framework classes fall through with [NotHandled].
 *
 * @property env device environment used for `Build` fields
 * @property syntheticCaller when set, `getStackTrace()` returns a fake trace whose frames belong to this
 *   class name, which satisfies caller checks in anti-tamper code
 */
class AndroidStubs(val env: AndroidEnv = AndroidEnv()) {

    private val methods = HashMap<String, StubHandler>()
    private val fields = HashMap<String, Any?>()

    var syntheticCaller: String? = null

    init { registerBuiltins() }

    /**
     * Drop all user registrations and restore the built-in handlers.
     */
    fun clear() {
        methods.clear()
        fields.clear()
        syntheticCaller = null
        registerBuiltins()
    }

    /**
     * Install [handler] for every overload of `classDesc->name`, replacing any previous handler.
     */
    fun registerMethod(classDesc: String, name: String, handler: StubHandler) {
        methods["$classDesc->$name"] = handler
    }

    /**
     * Set the value read for static field `classDesc.name`; takes precedence over [env].
     */
    fun registerField(classDesc: String, name: String, value: Any?) {
        fields["$classDesc.$name"] = value
    }

    /**
     * Whether [declClass] belongs to the Android framework namespaces handled by this class.
     */
    fun isFrameworkClass(declClass: String): Boolean = FRAMEWORK_PREFIXES.any { declClass.startsWith(it) }

    /**
     * Dispatch a static call to its handler.
     *
     * @return the handler result, or [NotHandled] for non-framework classes without a handler
     * @throws StubNotImplemented for framework methods without a handler
     */
    fun callStatic(ref: MethodRef, args: List<Any?>): Any? = dispatch(ref, null, args)

    /**
     * Dispatch an instance call to its handler; see [callStatic].
     */
    fun callInstance(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? = dispatch(ref, receiver, args)

    /**
     * Read a stubbed static field, falling back to [env]; [NotHandled] if neither defines it.
     */
    fun field(declClass: String, name: String): Any? {
        val k = "$declClass.$name"
        if (k in fields) return fields[k]
        return env.field(declClass, name)
    }

    private fun dispatch(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? {
        methods["${ref.declClass}->${ref.name}"]?.let { return it.invoke(receiver, args) }
        if (isFrameworkClass(ref.declClass)) throw StubNotImplemented("${ref.declClass}->${ref.shortId}")
        return NotHandled
    }

    private fun registerBuiltins() {
        registerMethod("Landroid/util/Base64;", "encodeToString") { _, a -> base64Encode(a, toStr = true) }
        registerMethod("Landroid/util/Base64;", "encode") { _, a -> base64Encode(a, toStr = false) }
        registerMethod("Landroid/util/Base64;", "decode") { _, a -> base64Decode(a) }
        for (m in listOf("d", "e", "i", "w", "v", "wtf", "println")) registerMethod("Landroid/util/Log;", m) { _, _ -> 0 }
        registerMethod("Landroid/util/Log;", "isLoggable") { _, _ -> false }
        registerMethod("Landroid/util/Log;", "getStackTraceString") { _, _ -> "" }
        for (cls in listOf("Ljava/lang/Thread;", "Ljava/lang/Throwable;", "Ljava/lang/Exception;", "Ljava/lang/RuntimeException;"))
            registerMethod(cls, "getStackTrace") { _, _ -> synthStack() }
    }

    private fun synthStack(): Any? {
        val c = syntheticCaller ?: return NotHandled
        return Array(16) { StackTraceElement(c, "m$it", "Source.java", it + 1) }
    }

    private fun base64Encode(args: List<Any?>, toStr: Boolean): Any? {
        val data = args.getOrNull(0) as? ByteArray ?: return UNKNOWN
        val e = encoder((args.getOrNull(1) as? Int) ?: 0)
        return if (toStr) e.encodeToString(data) else e.encode(data)
    }

    private fun base64Decode(args: List<Any?>): Any? {
        val d = decoder((args.getOrNull(1) as? Int) ?: 0)
        return when (val v = args.getOrNull(0)) {
            is String -> d.decode(v)
            is ByteArray -> d.decode(v)
            else -> UNKNOWN
        }
    }

    private fun encoder(flags: Int): java.util.Base64.Encoder {
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        return e
    }

    private fun decoder(flags: Int): java.util.Base64.Decoder =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()

    companion object {
        private const val NO_PADDING = 1
        private const val URL_SAFE = 8
        private val FRAMEWORK_PREFIXES = listOf("Landroid/", "Lcom/android/", "Ldalvik/", "Llibcore/")
    }
}
