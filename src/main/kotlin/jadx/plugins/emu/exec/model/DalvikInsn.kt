package jadx.plugins.emu.exec.model

import jadx.api.plugins.input.insns.Opcode

/**
 * Out-of-line data attached to an instruction (switch tables and `fill-array-data` payloads).
 */
sealed interface InsnPayload

/**
 * Payload of a `packed-switch` / `sparse-switch` instruction.
 *
 * @property keys case keys, parallel to [targets]
 * @property targets branch targets as offsets relative to the switch instruction
 */
class SwitchPayload(val keys: IntArray, val targets: IntArray) : InsnPayload

/**
 * Payload of a `fill-array-data` instruction.
 *
 * @property size number of elements
 * @property elementSize size of one element in bytes
 * @property data the decoded primitive array (e.g. `IntArray`, `ByteArray`), or null if unavailable
 */
class ArrayPayload(val size: Int, val elementSize: Int, val data: Any?) : InsnPayload

/**
 * One decoded Dalvik instruction.
 *
 * @property opcode normalized jadx opcode
 * @property offset code-unit offset of this instruction inside the method
 * @property regs register operands in encoding order; for defining instructions the destination is `regs[0]`
 * @property literal immediate literal for `const*` and `*-lit` instructions, otherwise 0
 * @property target absolute offset of the branch target for `goto`/`if-*`, or of the payload table for
 *   switch and `fill-array-data`; unused for other opcodes
 * @property ref the constant-pool item referenced by this instruction (string, type, field, method, call site), if any
 * @property payload resolved switch/array payload for instructions that have one
 */
class DalvikInsn(
    val opcode: Opcode,
    val offset: Int,
    val regs: IntArray,
    val literal: Long,
    val target: Int,
    val ref: InsnRef?,
    var payload: InsnPayload?,
)
