package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.input.DexInputSource
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.UnknownVal
import java.io.File

/**
 * Signatures of the Android framework (API 33), loaded from the bundled stub jar.
 *
 * Provides class hierarchy and method signatures only; behaviour comes from [AndroidStubs].
 */
object FrameworkStubs {

    /**
     * Signature-only source for the framework, or null if the stub jar could not be loaded.
     */
    val source: MethodSource? by lazy { loadFromResource() }

    /**
     * Framework method signature, or null if unknown.
     */
    fun method(classDesc: String, shortId: String): DexMethod? = source?.method(classDesc, shortId)

    /**
     * Whether [classDesc] is a framework class.
     */
    fun hasClass(classDesc: String): Boolean = source?.classInfo(classDesc) != null

    /**
     * Call a framework method through [android]'s handlers.
     *
     * @return the handler result, or an [UnknownVal] if the handler is missing or fails
     * @throws IllegalArgumentException if the method signature does not exist in the framework
     */
    fun call(android: AndroidStubs, classDesc: String, shortId: String, receiver: Any?, args: List<Any?>): Any? {
        val m = method(classDesc, shortId)
            ?: throw IllegalArgumentException("no framework member $classDesc->$shortId (check the signature)")
        val r = runCatching {
            if (m.isStatic) android.callStatic(m.ref, args) else android.callInstance(m.ref, receiver, args)
        }.getOrElse { UnknownVal(m.ref.returnType) }
        return if (r === NotHandled) UnknownVal(m.ref.returnType) else r
    }

    private fun loadFromResource(): MethodSource? = runCatching {
        val stream = FrameworkStubs::class.java.getResourceAsStream("/framework/android-13-stub.jar") ?: return null
        val tmp = File.createTempFile("jdex-android-stub", ".jar").apply { deleteOnExit() }
        stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        try {
            DexInputSource.loadFramework(tmp)
        } finally {
            tmp.delete()
        }
    }.getOrNull()
}
