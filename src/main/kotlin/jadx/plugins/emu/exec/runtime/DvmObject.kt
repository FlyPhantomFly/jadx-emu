package jadx.plugins.emu.exec.runtime

/**
 * An instance of an application (dex) class created during emulation.
 *
 * Host (JVM) objects are represented directly by their Java value instead.
 *
 * @property type descriptor of the runtime class
 * @property fields instance field values keyed by [jadx.plugins.emu.exec.model.FieldRef.key]
 */
class DvmObject(val type: String) {
    val fields = HashMap<String, Any?>()
}

/**
 * A host-class object allocated by `new-instance` whose constructor has not run yet.
 * It is replaced by the real Java object once `<init>` is invoked.
 *
 * @property type descriptor of the class
 */
class UninitHost(val type: String)

/**
 * An exception raised inside emulated code and not caught by it.
 *
 * @property type descriptor of the thrown exception class
 * @property obj the emulated exception object (a [DvmObject] or host `Throwable`), if available
 */
class DvmThrowable(val type: String, message: String?, val obj: Any? = null) : RuntimeException(message)

/**
 * Static-initializer state of a class inside a [jadx.plugins.emu.exec.Vm].
 */
enum class ClinitState { RUNNING, DONE }
