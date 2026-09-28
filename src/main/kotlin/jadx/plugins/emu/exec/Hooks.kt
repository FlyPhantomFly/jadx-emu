package jadx.plugins.emu.exec

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A method invocation presented to an [Interceptor].
 *
 * @property method descriptor of the invoked method (`Lcls;->name(args)ret`)
 * @property receiver the `this` value, or null for static calls
 * @property args argument values; mutable so interceptors can rewrite them before the call proceeds
 * @property replaced whether an interceptor supplied the result via [replace]
 * @property result the replacement result, valid when [replaced] is true
 */
class HookCall(val method: String, val receiver: Any?, val args: MutableList<Any?>) {
    var replaced: Boolean = false
        private set
    var result: Any? = null
        private set

    /**
     * Overwrite argument [index]; out-of-range indices are ignored.
     */
    fun setArg(index: Int, value: Any?) { if (index in args.indices) args[index] = value }

    /**
     * Skip the real invocation and return [value] to the caller instead.
     */
    fun replace(value: Any?) { replaced = true; result = value }
}

/**
 * Callback invoked before a hooked method runs.
 */
fun interface Interceptor { fun onInvoke(call: HookCall) }

/**
 * Registry of [Interceptor]s keyed by method descriptor, attached via [Vm.hooks].
 *
 * Thread-safe. Multiple interceptors on the same descriptor run in registration order.
 */
class HookRegistry {
    private val counter = AtomicInteger(0)
    private val byDescriptor = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<Int, Interceptor>>>()
    private val idToDescriptor = ConcurrentHashMap<Int, String>()

    /**
     * Register [hook] for [descriptor].
     *
     * @return an id usable with [remove]
     */
    fun add(descriptor: String, hook: Interceptor): Int {
        val id = counter.incrementAndGet()
        byDescriptor.getOrPut(descriptor) { CopyOnWriteArrayList() }.add(id to hook)
        idToDescriptor[id] = descriptor
        return id
    }

    /**
     * Unregister the hook with the given [id].
     *
     * @return false if no such hook exists
     */
    fun remove(id: Int): Boolean {
        val descriptor = idToDescriptor.remove(id) ?: return false
        byDescriptor[descriptor]?.removeIf { it.first == id }
        return true
    }

    /**
     * Interceptors registered for [descriptor], in registration order.
     */
    fun interceptors(descriptor: String): List<Interceptor> =
        byDescriptor[descriptor]?.map { it.second } ?: emptyList()

    /**
     * All registered hooks as (id, descriptor) pairs.
     */
    fun list(): List<Pair<Int, String>> = idToDescriptor.entries.map { it.key to it.value }

    /**
     * Remove every hook.
     */
    fun clear() {
        byDescriptor.clear()
        idToDescriptor.clear()
    }

    fun isEmpty(): Boolean = idToDescriptor.isEmpty()
}
