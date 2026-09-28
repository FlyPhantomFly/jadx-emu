package jadx.plugins.emu.exec

/**
 * Resource limits for one emulation run.
 *
 * @property maxSteps maximum instructions executed per method activation before aborting
 * @property maxMillis wall-clock deadline for a top-level invoke; 0 or negative disables the deadline
 * @property maxDepth maximum nested call depth; deeper calls return an unknown value instead of executing
 */
class ExecLimits(
    val maxSteps: Int = 500_000,
    val maxMillis: Long = 2_000,
    val maxDepth: Int = 64,
)
