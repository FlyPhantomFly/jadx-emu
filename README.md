# jadx-emu

A jadx plugin that runs Dalvik code in an emulator. Extensions plug into it to do things like decrypt strings or unpack hidden dex files.

## Install

You need jadx 1.5.6 and JDK 21.

```
./gradlew shadowJar publishToMavenLocal
jadx plugins --install-jar "$PWD/build/libs/jadx-emu-0.2.0-plugin.jar"
```

`shadowJar` builds the plugin jar. `publishToMavenLocal` puts the engine in `~/.m2` so extensions can build against it. Keep exactly one jadx-emu plugin jar installed.

When jadx starts it logs `jadx-emu 0.2.0 loaded: N extension(s) found in ..., N active`. That's the line to check.

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

## Writing an extension

An extension is a jar in the extensions directory with a class that implements `jadx.plugins.emu.api.EmuExtension`, listed in `META-INF/services/jadx.plugins.emu.api.EmuExtension`.

```kotlin
dependencies {
    compileOnly("io.github.flyphantomfly:jadx-emu:0.2.0")
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

`ctx.emu` gives the pass access to the emulator. See `jadx.plugins.emu.api.EmuContext`. `Hooks.after(pattern) { result -> ... }` lets a handler see or change what a call actually returned.

## Credits and license

jadx-emu was started by nitanmarcel. This copy is maintained separately by FlyPhantomFly and picks up upstream fixes as they apply. GPLv2, see `LICENSE` and `NOTICE`.
