# Changes

## 0.4.0

Carries over what upstream added in beta.7, so the same calls are available here.

- `emu.loadDex(file)` and `emu.loadDex(bytes)` load extra code, for example a dex recovered at runtime. `emu.fork(...)` makes a child emulator with the same host policy, device values and diagnostics but its own static fields and, unless you pass some, its own hooks. `a + b` stacks method sources, trying the left side first, so a patch layer can replace one method and leave the rest of the class to the layers behind it.
- `Hooks.hookAfter(pattern)` is upstream's return-value hook. The handler reads `result` and calls `replace(...)` to change it. It runs on every path a call can complete on, including after a `hook` replaced the call and when a host object is constructed, and the first handler to replace ends the chain. Our own `Hooks.after` stays as it was, and when a call has both kinds, `after` runs first.
- `Vm.construct`, `getStatic`, `setStatic`, `getField` and `setField` create objects and read or write fields without running emulated code, and `isUnknown()` tests a value.
- `emu.host`, `emu.diagnostics`, `emu.method(descriptor)` and `emu.method(target)`.

Left out on purpose: upstream's Java-friendly reshaping (`Runnable`, `Consumer` and `Predicate` in place of Kotlin lambdas), its `internal` tightening of classes such as `NotHandled`, and its removal of `emu.world` and `emu.engine`. Extensions use all of those as they are, and taking them would break the extensions.

## 0.3.0

Extensions can now share a native backend. One extension registers it with `registerNativeBackend`, and any other looks it up with `emu.nativeBackend(id)`, so a backend such as unidbg can live in its own jar. Nothing that exists changes, and extensions built against 0.2.0 keep working.

The `NativeBridge` docs called the class name a descriptor. It's the internal name, `com/foo/Bar`. The code was already right.

## 0.2.0

The first release of the independent line, forked from nitanmarcel's jadx-emu (GPLv2) at upstream commit ba65619, plus the `Hooks.after` hook. Published as `io.github.flyphantomfly:jadx-emu`.
