package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UninitHost
import jadx.plugins.emu.exec.runtime.UnknownVal

/**
 * Runs host (JVM) methods for the emulator via reflection.
 *
 * Every call first consults [stubs]; otherwise it proceeds only if [policy] allows the class and method
 * and all arguments are concrete host values. Anything else yields an [UnknownVal].
 */
class HostExec(private val policy: HostBoundary, private val stubs: AndroidStubs = AndroidStubs()) {

    /**
     * Invoke a static method.
     */
    fun invokeStatic(ref: MethodRef, args: List<Any?>): Any? {
        val sv = stubs.callStatic(ref, args)
        if (sv !== NotHandled) return sv
        if (!argsUsable(args)) return UnknownVal(ref.returnType)
        if (!handleable(ref)) return UnknownVal(ref.returnType)
        return reflectInvoke(ref, null, args)
    }

    /**
     * Invoke an instance method on a host object.
     */
    fun invokeInstance(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? {
        val sv = stubs.callInstance(ref, receiver, args)
        if (sv !== NotHandled) return sv
        if (!usable(receiver) || !argsUsable(args)) return UnknownVal(ref.returnType)
        val eff = redispatch(ref, receiver)
        if (!handleable(eff)) return UnknownVal(ref.returnType)
        return reflectInvoke(eff, receiver, args)
    }

    private fun redispatch(ref: MethodRef, receiver: Any?): MethodRef =
        if (ref.declClass == "Ljava/lang/Object;" && receiver is String) ref.copy(declClass = "Ljava/lang/String;") else ref

    /**
     * Instantiate host class [type] using the constructor described by [ref].
     */
    fun construct(type: String, ref: MethodRef, args: List<Any?>): Any? {
        if (!policy.canHandle(type) || policy.isBlocked(type, ref.name) || !argsUsable(args)) return UnknownVal(type)
        return runCatching {
            val ctor = hostClass(type).getDeclaredConstructor(*paramClasses(ref.argTypes))
            ctor.isAccessible = true
            ctor.newInstance(*marshalArgs(ref.argTypes, args))
        }.getOrElse { UnknownVal(type) }
    }

    private fun reflectInvoke(ref: MethodRef, receiver: Any?, args: List<Any?>): Any? = runCatching {
        hostClass(ref.declClass).getMethod(ref.name, *paramClasses(ref.argTypes))
            .invoke(receiver, *marshalArgs(ref.argTypes, args))
    }.getOrElse { UnknownVal(ref.returnType) }

    private fun handleable(ref: MethodRef) = policy.canHandle(ref.declClass) && !policy.isBlocked(ref.declClass, ref.name)
    private fun usable(v: Any?) = v !is UnknownVal && v !is DvmObject && v !is UninitHost
    private fun argsUsable(args: List<Any?>) = args.all { usable(it) }

    private fun paramClasses(types: List<String>): Array<Class<*>> = Array(types.size) { descClass(types[it]) }
    private fun marshalArgs(types: List<String>, args: List<Any?>): Array<Any?> = Array(types.size) { marshal(types[it], args[it]) }

    private fun marshal(desc: String, v: Any?): Any? = when (desc) {
        "I" -> (v as Number).toInt()
        "J" -> (v as Number).toLong()
        "S" -> (v as Number).toShort()
        "B" -> (v as Number).toByte()
        "F" -> when (v) { is Int -> Float.fromBits(v); is Float -> v; else -> (v as Number).toFloat() }
        "D" -> when (v) { is Long -> Double.fromBits(v); is Double -> v; else -> (v as Number).toDouble() }
        "Z" -> when (v) { is Boolean -> v; is Number -> v.toInt() != 0; else -> false }
        "C" -> when (v) { is Char -> v; is Number -> v.toInt().toChar(); else -> '\u0000' }
        else -> v
    }

    private fun descClass(desc: String): Class<*> = when (desc) {
        "I" -> Integer.TYPE; "J" -> java.lang.Long.TYPE; "Z" -> java.lang.Boolean.TYPE
        "B" -> java.lang.Byte.TYPE; "C" -> Character.TYPE; "S" -> java.lang.Short.TYPE
        "F" -> java.lang.Float.TYPE; "D" -> java.lang.Double.TYPE; "V" -> Void.TYPE
        else -> if (desc.startsWith("[")) Class.forName(desc.replace('/', '.'), false, HostExec::class.java.classLoader)
        else hostClass(desc)
    }

}

/**
 * Load the host class for a type descriptor such as `Ljava/lang/String;` without initializing it.
 *
 * @throws ClassNotFoundException if the class is not on the host classpath
 */
fun hostClass(desc: String): Class<*> =
    Class.forName(desc.removePrefix("L").removeSuffix(";").replace('/', '.'), false, HostExec::class.java.classLoader)
