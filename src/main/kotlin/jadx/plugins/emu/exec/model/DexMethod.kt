package jadx.plugins.emu.exec.model

/**
 * One catch handler of a [TryBlock].
 *
 * @property type descriptor of the caught exception type, or null for a catch-all handler
 * @property offset instruction offset where the handler starts
 */
class Handler(val type: String?, val offset: Int)

/**
 * A protected code range and its handlers.
 *
 * @property start first covered instruction offset (inclusive)
 * @property end last covered instruction offset (inclusive)
 * @property handlers handlers in lookup order; a catch-all, if present, is last
 */
class TryBlock(val start: Int, val end: Int, val handlers: List<Handler>)

/**
 * A method with its decoded instruction stream, as consumed by the emulator.
 *
 * Methods loaded in signature-only mode (see `DexInputSource.loadFramework`) have an empty [insns] list.
 *
 * @property declClass descriptor of the declaring class (`Lcom/foo/Bar;`)
 * @property ref method reference (name, argument and return types)
 * @property isStatic whether the method is static (no `this` register)
 * @property registersCount total number of registers in the frame
 * @property paramWords number of register words occupied by parameters, including `this`; parameters start
 *   at register `registersCount - paramWords`
 * @property insns instructions in code order
 * @property offsetToIndex map from instruction offset to its index in [insns]
 * @property tries try/catch ranges
 * @property codeOffset offset of the code item in the dex file
 */
class DexMethod(
    val declClass: String,
    val ref: MethodRef,
    val isStatic: Boolean,
    val registersCount: Int,
    val paramWords: Int,
    val insns: List<DalvikInsn>,
    val offsetToIndex: Map<Int, Int>,
    val tries: List<TryBlock>,
    val codeOffset: Int,
)
