package jadx.plugins.emu.api

import jadx.plugins.emu.exec.NativeBridge
import java.io.File

/**
 * Runs native (JNI) methods for the emulator, for example with a native emulator such as unidbg.
 *
 * An extension that provides one registers it with [EmuExtensionContext.registerNativeBackend]. An extension that needs native code to run looks it up with [EmuContext.nativeBackend] when it is about to create a VM, opens a bridge, and passes that bridge to the engine's `newVm(nativeBridge = ...)`.
 */
interface NativeBackend {
    /**
     * Short, stable name that other extensions look the backend up by, for example `unidbg`.
     */
    val id: String

    /**
     * Opens a bridge for one app.
     *
     * The bridge must run only the methods in [NativeRequest.methods] and answer [jadx.plugins.emu.exec.NotHandled] for every other native call, so the rest of the app is emulated exactly as it would be without a backend.
     *
     * @throws IllegalArgumentException when the request can't be served, for example a library is missing from the apk
     */
    fun open(request: NativeRequest): NativeBridge
}

/**
 * What a [NativeBackend] needs to serve one app.
 *
 * Both [methods] and [libs] are required. Loading every library of an app runs all of their `JNI_OnLoad` code, which is rarely what you want, so the caller says which ones.
 *
 * @property apk the app the native libraries are taken from.
 * @property methods the native methods to run, as `Lpkg/Cls;->name`. Every overload of a name is included.
 * @property libs file names of the libraries to load from `lib/<abi>/`, with the primary one first.
 * @property abi which `lib/<abi>/` folder of the apk to use.
 * @property options settings specific to the backend, such as an Android SDK level or tracing.
 */
data class NativeRequest(
    val apk: File,
    val methods: Set<String>,
    val libs: List<String>,
    val abi: String = "arm64-v8a",
    val options: Map<String, String> = emptyMap(),
) {
    init {
        require(methods.isNotEmpty()) { "NativeRequest.methods is empty: nothing to run" }
        require(libs.isNotEmpty()) { "NativeRequest.libs is empty: name the libraries to load" }
        require(abi.isNotBlank()) { "NativeRequest.abi is blank" }
    }
}
