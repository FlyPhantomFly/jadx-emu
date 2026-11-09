# jadx-emu

A jadx plugin that runs Dalvik code in an emulator. Extensions plug into it to do things like decrypt strings or unpack hidden dex files.

## Install

You need jadx 1.5.6 and JDK 21.

```
./gradlew shadowJar publishToMavenLocal
jadx plugins --install-jar "$PWD/build/libs/jadx-emu-0.4.0-plugin.jar"
```

`shadowJar` builds the plugin jar. `publishToMavenLocal` puts the engine in `~/.m2` so extensions can build against it. Keep exactly one jadx-emu plugin jar installed.

When jadx starts it logs `jadx-emu 0.4.0 loaded: N extension(s) found in ..., N active`. That's the line to check.

## Using extensions

Extensions are jars in `~/.config/jadx/plugins-data/jadx-emu/extensions/`.

An extension that runs on every project (AUTO) stays off until you enable it by id. On the command line:

```
jadx -P"jadx-emu.extensions.enabled=strings" -d out app.apk
```

Use a comma-separated list for more than one. On-demand extensions work from the code viewer's context menu and don't need this.

An extension's own settings use the prefix `jadx-emu.<extension id>.`, for example `jadx-emu.strings.rewrite`.

## Extensions

- [jadx-emu-dexguard-unpacker](https://github.com/FlyPhantomFly/jadx-emu-dexguard-unpacker) recovers the hidden dex files a DexGuard app decrypts at runtime.
- [jadx-emu-string-deobfuscator](https://github.com/FlyPhantomFly/jadx-emu-string-deobfuscator) decrypts strings and writes them back into the decompiled code.
- [jadx-emu-string-sites](https://github.com/FlyPhantomFly/jadx-emu-string-sites) reports which strings an app decrypts and which ones it couldn't recover.

## Running recovered code

If an extension recovers more code at runtime, for example a hidden dex it decrypted, it can load and run it. `emu.loadDex(file)` and `emu.loadDex(bytes)` return a method source. Stack it onto the app with `emu.source + loaded`, which tries the left side first, so a patch layer can replace one method and leave the rest of a class to the layers behind it. `emu.fork(source = ...)` runs it in a child emulator that shares the host policy, device values and diagnostics but keeps its own static fields and hooks, so it doesn't disturb the main state.

```kotlin
import jadx.plugins.emu.exec.plus

val hidden = emu.source + emu.loadDex(recoveredBytes)
val child = emu.fork(source = hidden)
val value = child.newVm().invoke(child.method("com.app.Secret", "key()Ljava/lang/String;")!!)
```

`Vm` can also build objects and touch fields without running emulated code: `construct`, `getStatic`, `setStatic`, `getField` and `setField`.

## Writing an extension

An extension is a jar in the extensions directory with a class that implements `jadx.plugins.emu.api.EmuExtension`, listed in `META-INF/services/jadx.plugins.emu.api.EmuExtension`.

```kotlin
dependencies {
    compileOnly("io.github.flyphantomfly:jadx-emu:0.4.0")
    compileOnly("io.github.skylot:jadx-core:1.5.6")
}
```

Automatic, runs on every project where the user enables it:

```kotlin
class MyExtension : EmuExtension {
    override fun info() = EmuExtensionInfo("my-ext", "My extension", "Does something", ExtensionMode.AUTO)

    override fun init(ctx: EmuExtensionContext) {
        ctx.addPass(MyPass(ctx))
    }
}
```

On demand, acts only on the classes and methods the user picks from the code viewer's context menu:

```kotlin
class MyExtension : EmuExtension {
    override fun info() = EmuExtensionInfo("my-ext", "My extension", "Does something", ExtensionMode.ON_DEMAND)

    override fun init(ctx: EmuExtensionContext) {
        ctx.addTargetToggleAction("My extension here")
        ctx.addPass(MyPass(ctx))   // checks ctx.targets.appliesTo(mth) before acting
    }
}
```

`ctx.emu` gives the pass access to the emulator. See `jadx.plugins.emu.api.EmuContext`. To see or change what a call returned, use `hooks.hookAfter(pattern) { ... result ...; replace(x) }`, or `hooks.after(pattern) { result -> newResult }`. Both run only for calls made from emulated code, not for the method you start a VM with.

## Native backends

Some apps decrypt in native code. A native backend runs those JNI methods for the emulator, for example with unidbg. It's an extension that registers itself:

```kotlin
ctx.registerNativeBackend(MyBackend())
```

An extension that needs native code to run looks the backend up when it's about to create a VM, and passes the bridge it gets to `newVm`:

```kotlin
val backend = ctx.emu.nativeBackend("unidbg")
val bridge = backend?.open(NativeRequest(apk, setOf("Lcom/foo/Bar;->decrypt"), listOf("libfoo.so")))
val vm = ctx.emu.engine.newVm(nativeBridge = bridge)
```

Look it up when you use it, not in `init`. Extensions initialize in the order of their jar names, so the one that provides the backend may not be ready yet. A request has to name both the methods and the libraries. Any native method that isn't named is left alone and behaves exactly as it does with no backend.

## Credits and license

jadx-emu was started by nitanmarcel. This copy is maintained separately by FlyPhantomFly and picks up upstream fixes as they apply. GPLv2, see `LICENSE` and `NOTICE`.
