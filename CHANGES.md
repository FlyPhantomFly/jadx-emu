# Changes

## 0.3.0

Extensions can now share a native backend. One extension registers it with `registerNativeBackend`, and any other looks it up with `emu.nativeBackend(id)`, so a backend such as unidbg can live in its own jar. Nothing that exists changes, and extensions built against 0.2.0 keep working.

The `NativeBridge` docs called the class name a descriptor. It's the internal name, `com/foo/Bar`. The code was already right.

## 0.2.0

The first release of the independent line, forked from nitanmarcel's jadx-emu (GPLv2) at upstream commit ba65619, plus the `Hooks.after` hook. Published as `io.github.flyphantomfly:jadx-emu`.
