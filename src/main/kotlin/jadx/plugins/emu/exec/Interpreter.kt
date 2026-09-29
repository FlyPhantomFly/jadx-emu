package jadx.plugins.emu.exec

import jadx.plugins.emu.exec.model.ArrayPayload
import jadx.plugins.emu.exec.model.CallSiteRef
import jadx.plugins.emu.exec.model.DalvikInsn
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.model.FieldRef
import jadx.plugins.emu.exec.model.Handler
import jadx.plugins.emu.exec.model.MethodRef
import jadx.plugins.emu.exec.model.StringRef
import jadx.plugins.emu.exec.model.SwitchPayload
import jadx.plugins.emu.exec.model.TypeRef
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.DvmThrowable
import jadx.plugins.emu.exec.runtime.UninitHost
import jadx.plugins.emu.exec.runtime.UnknownVal
import jadx.api.plugins.input.insns.Opcode

private sealed interface Step
private class Next(val pc: Int) : Step
private class Done(val value: Any?) : Step

/**
 * Outcome of one abstract step.
 *
 * @property successors offsets of the instructions control may reach next; empty when the method returns or throws
 * @property returnVal the returned value when [returns] is true
 * @property returns whether the instruction leaves the method
 */
class AbsResult(val successors: IntArray, val returnVal: Any?, val returns: Boolean)

private val EMPTY_INTS = IntArray(0)

/**
 * Supplies the result of an invoke during abstract interpretation.
 */
fun interface CallResolver {
    /**
     * @return the call result and its type descriptor; the result may be an [UnknownVal]
     */
    fun resolve(insn: DalvikInsn, frame: Frame): Pair<Any?, String?>
}

/**
 * Object and field model used during abstract interpretation.
 */
interface AbsHeap {
    /**
     * @param site allocation site as `Lcls;#shortId@offset`
     */
    fun newInstance(site: String, type: String): Any?
    fun iget(obj: Any?, key: String, type: String): Any?
    fun iput(obj: Any?, key: String, v: Any?)
    fun sget(declClass: String, key: String, type: String): Any?
    fun sput(declClass: String, key: String, v: Any?)
}

/**
 * Instruction executor behind [Vm]. Most callers should use [Vm.invoke] instead.
 */
class Interpreter(private val vm: Vm) {

    private val fallCache = HashMap<DexMethod, Array<IntArray>>()

    /**
     * Execute [method] to completion in a fresh frame.
     *
     * @return the return value, or null for `void`
     * @throws VmAbort if a limit is exceeded
     * @throws jadx.plugins.emu.exec.runtime.DvmThrowable if emulated code throws and does not catch
     */
    fun run(method: DexMethod, args: List<Any?>, receiver: Any?): Any? {
        val frame = Frame(method.registersCount)
        bindParams(frame, method, args, receiver)
        val hook = vm.hook
        hook?.onEnter(method, frame)
        try {
            var pc = 0
            var steps = 0
            while (true) {
                if (steps++ > vm.limits.maxSteps || vm.deadlineExceeded()) throw VmAbort("step limit")
                if (pc !in method.insns.indices) throw VmAbort("pc out of range")
                val insn = method.insns[pc]
                hook?.onStep(method, insn, frame, pc)
                when (val s = exec(method, insn, frame, pc)) {
                    is Next -> {
                        if (insn.resultReg >= 0 && s.pc == pc + 1) applyResultReg(insn, frame)
                        pc = s.pc
                    }
                    is Done -> return s.value
                }
            }
        } finally {
            hook?.onExit(method)
        }
    }

    /**
     * Store [receiver] and [args] into the parameter registers of [frame], coercing each argument to
     * its declared type.
     */
    fun bindParams(frame: Frame, method: DexMethod, args: List<Any?>, receiver: Any?) {
        var reg = method.argsStartReg
        if (!method.isStatic) { frame.set(reg, receiver); reg++ }
        var i = 0
        for (t in method.ref.argTypes) {
            val v = retype(args.getOrNull(i), t)
            if (t == "J" || t == "D") { frame.setWide(reg, v); reg += 2 } else { frame.set(reg, v); reg++ }
            i++
        }
    }

    private fun exec(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int): Step {
        val r = insn.regs
        when (insn.opcode) {
            Opcode.RETURN -> return Done(retype(frame.get(r[0]), method.ref.returnType))
            Opcode.RETURN_VOID -> return Done(null)
            Opcode.GOTO -> return Next(idx(method, insn.target))
            Opcode.IF_EQ -> return branch(method, insn, pc, eq(frame.get(r[0]), frame.get(r[1])))
            Opcode.IF_NE -> return branch(method, insn, pc, !eq(frame.get(r[0]), frame.get(r[1])))
            Opcode.IF_LT -> return branch(method, insn, pc, ci2(frame.get(r[0])) < ci2(frame.get(r[1])))
            Opcode.IF_GE -> return branch(method, insn, pc, ci2(frame.get(r[0])) >= ci2(frame.get(r[1])))
            Opcode.IF_GT -> return branch(method, insn, pc, ci2(frame.get(r[0])) > ci2(frame.get(r[1])))
            Opcode.IF_LE -> return branch(method, insn, pc, ci2(frame.get(r[0])) <= ci2(frame.get(r[1])))
            Opcode.IF_EQZ -> return branch(method, insn, pc, isZero(frame.get(r[0])))
            Opcode.IF_NEZ -> return branch(method, insn, pc, !isZero(frame.get(r[0])))
            Opcode.IF_LTZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) < 0)
            Opcode.IF_GEZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) >= 0)
            Opcode.IF_GTZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) > 0)
            Opcode.IF_LEZ -> return branch(method, insn, pc, ci2(frame.get(r[0])) <= 0)
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                val sw = insn.payload as? SwitchPayload ?: return Next(pc + 1)
                val sel = frame.get(r[0])
                if (sel.unk()) throw VmAbort("unknown switch selector")
                val k = sw.keys.indexOf(ci(sel))
                return if (k < 0) Next(pc + 1) else Next(idx(method, insn.offset + sw.targets[k]))
            }
            Opcode.DIV_INT -> return divInt(method, insn, pc, frame) { a, b -> a / b }
            Opcode.REM_INT -> return divInt(method, insn, pc, frame) { a, b -> a % b }
            Opcode.DIV_INT_LIT -> return divIntLit(method, insn, pc, frame, insn.literal.toInt()) { a, b -> a / b }
            Opcode.REM_INT_LIT -> return divIntLit(method, insn, pc, frame, insn.literal.toInt()) { a, b -> a % b }
            Opcode.DIV_LONG -> return divLong(method, insn, pc, frame) { a, b -> a / b }
            Opcode.REM_LONG -> return divLong(method, insn, pc, frame) { a, b -> a % b }
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> return invoke(method, insn, frame, pc, hasReceiver = false, virtual = false, superCall = false)
            Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> return invoke(method, insn, frame, pc, hasReceiver = true, virtual = false, superCall = false)
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE ->
                return invoke(method, insn, frame, pc, hasReceiver = true, virtual = true, superCall = false)
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> return invoke(method, insn, frame, pc, hasReceiver = true, virtual = false, superCall = true)
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> { invokeCustom(insn, frame); return Next(pc + 1) }
            Opcode.CHECK_CAST -> {
                val o = frame.get(r[0]); val ct = (insn.ref as TypeRef).desc
                if (!o.unk() && o != null) {
                    val vt = valueType(o)
                    if (vt != null && !isAssignable(vt, ct)) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ClassCastException;", null), frame)
                }
                return Next(pc + 1)
            }
            Opcode.THROW -> return routeThrow(method, insn.offset, asThrowable(frame.get(r[0])), frame)
            else -> { dataTransfer(insn, frame); return Next(pc + 1) }
        }
    }

    private fun dataTransfer(insn: DalvikInsn, frame: Frame) {
        val r = insn.regs
        when (insn.opcode) {
            Opcode.NOP -> {}
            Opcode.CONST -> frame.set(r[0], insn.literal.toInt())
            Opcode.CONST_WIDE -> frame.setWide(r[0], insn.literal)
            Opcode.CONST_STRING -> frame.set(r[0], (insn.ref as StringRef).value)
            Opcode.CONST_CLASS -> frame.set(r[0], DvmClass((insn.ref as TypeRef).desc))
            Opcode.MOVE, Opcode.MOVE_OBJECT -> frame.set(r[0], frame.get(r[1]))
            Opcode.MOVE_WIDE -> frame.setWide(r[0], frame.get(r[1]))
            Opcode.MOVE_RESULT -> {
                val t = frame.resultType
                if (t == "J" || t == "D") frame.setWide(r[0], frame.result) else frame.set(r[0], retype(frame.result, t))
                frame.result = null; frame.resultType = null
            }
            Opcode.MOVE_EXCEPTION -> {
                val pe = frame.pendingException
                frame.set(r[0], (pe as? DvmThrowable)?.let { it.obj ?: it } ?: pe ?: UnknownVal("Ljava/lang/Throwable;"))
                frame.pendingException = null
            }

            Opcode.ADD_INT -> binInt(frame, r) { a, b -> a + b }
            Opcode.SUB_INT -> binInt(frame, r) { a, b -> a - b }
            Opcode.MUL_INT -> binInt(frame, r) { a, b -> a * b }
            Opcode.AND_INT -> binInt(frame, r) { a, b -> a and b }
            Opcode.OR_INT -> binInt(frame, r) { a, b -> a or b }
            Opcode.XOR_INT -> binInt(frame, r) { a, b -> a xor b }
            Opcode.SHL_INT -> binInt(frame, r) { a, b -> a shl (b and 0x1f) }
            Opcode.SHR_INT -> binInt(frame, r) { a, b -> a shr (b and 0x1f) }
            Opcode.USHR_INT -> binInt(frame, r) { a, b -> a ushr (b and 0x1f) }
            Opcode.ADD_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a + b }
            Opcode.RSUB_INT -> litInt(frame, r, insn.literal.toInt()) { a, b -> b - a }
            Opcode.MUL_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a * b }
            Opcode.AND_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a and b }
            Opcode.OR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a or b }
            Opcode.XOR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a xor b }
            Opcode.SHL_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a shl (b and 0x1f) }
            Opcode.SHR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a shr (b and 0x1f) }
            Opcode.USHR_INT_LIT -> litInt(frame, r, insn.literal.toInt()) { a, b -> a ushr (b and 0x1f) }
            Opcode.NEG_INT -> unInt(frame, r) { -it }
            Opcode.NOT_INT -> unInt(frame, r) { it.inv() }

            Opcode.ADD_LONG -> binLong(frame, r) { a, b -> a + b }
            Opcode.SUB_LONG -> binLong(frame, r) { a, b -> a - b }
            Opcode.MUL_LONG -> binLong(frame, r) { a, b -> a * b }
            Opcode.AND_LONG -> binLong(frame, r) { a, b -> a and b }
            Opcode.OR_LONG -> binLong(frame, r) { a, b -> a or b }
            Opcode.XOR_LONG -> binLong(frame, r) { a, b -> a xor b }
            Opcode.SHL_LONG -> binLongShift(frame, r) { a, b -> a shl b }
            Opcode.SHR_LONG -> binLongShift(frame, r) { a, b -> a shr b }
            Opcode.USHR_LONG -> binLongShift(frame, r) { a, b -> a ushr b }
            Opcode.NEG_LONG -> { val a = frame.get(r[1]); frame.setWide(r[0], if (a.unk()) UnknownVal("J") else -cl(a)) }
            Opcode.NOT_LONG -> { val a = frame.get(r[1]); frame.setWide(r[0], if (a.unk()) UnknownVal("J") else cl(a).inv()) }
            Opcode.CMP_LONG -> { val a = frame.get(aReg(r)); val b = frame.get(bReg(r)); frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else cl(a).compareTo(cl(b))) }

            Opcode.ADD_FLOAT -> binFloat(frame, r) { a, b -> a + b }
            Opcode.SUB_FLOAT -> binFloat(frame, r) { a, b -> a - b }
            Opcode.MUL_FLOAT -> binFloat(frame, r) { a, b -> a * b }
            Opcode.DIV_FLOAT -> binFloat(frame, r) { a, b -> a / b }
            Opcode.REM_FLOAT -> binFloat(frame, r) { a, b -> a % b }
            Opcode.NEG_FLOAT -> { val a = frame.get(r[1]); frame.set(r[0], if (a.unk()) UnknownVal("F") else -cf(a)) }
            Opcode.ADD_DOUBLE -> binDouble(frame, r) { a, b -> a + b }
            Opcode.SUB_DOUBLE -> binDouble(frame, r) { a, b -> a - b }
            Opcode.MUL_DOUBLE -> binDouble(frame, r) { a, b -> a * b }
            Opcode.DIV_DOUBLE -> binDouble(frame, r) { a, b -> a / b }
            Opcode.REM_DOUBLE -> binDouble(frame, r) { a, b -> a % b }
            Opcode.NEG_DOUBLE -> { val a = frame.get(r[1]); frame.setWide(r[0], if (a.unk()) UnknownVal("D") else -cd(a)) }
            Opcode.CMPL_FLOAT -> cmpF(frame, r, -1)
            Opcode.CMPG_FLOAT -> cmpF(frame, r, 1)
            Opcode.CMPL_DOUBLE -> cmpD(frame, r, -1)
            Opcode.CMPG_DOUBLE -> cmpD(frame, r, 1)

            Opcode.INT_TO_LONG -> conv(frame, r, "J") { ci(it).toLong() }
            Opcode.INT_TO_FLOAT -> conv(frame, r, "F") { ci(it).toFloat() }
            Opcode.INT_TO_DOUBLE -> conv(frame, r, "D") { ci(it).toDouble() }
            Opcode.LONG_TO_INT -> conv(frame, r, "I") { cl(it).toInt() }
            Opcode.LONG_TO_FLOAT -> conv(frame, r, "F") { cl(it).toFloat() }
            Opcode.LONG_TO_DOUBLE -> conv(frame, r, "D") { cl(it).toDouble() }
            Opcode.FLOAT_TO_INT -> conv(frame, r, "I") { cf(it).toInt() }
            Opcode.FLOAT_TO_LONG -> conv(frame, r, "J") { cf(it).toLong() }
            Opcode.FLOAT_TO_DOUBLE -> conv(frame, r, "D") { cf(it).toDouble() }
            Opcode.DOUBLE_TO_INT -> conv(frame, r, "I") { cd(it).toInt() }
            Opcode.DOUBLE_TO_LONG -> conv(frame, r, "J") { cd(it).toLong() }
            Opcode.DOUBLE_TO_FLOAT -> conv(frame, r, "F") { cd(it).toFloat() }
            Opcode.INT_TO_BYTE -> conv(frame, r, "B") { ci(it).toByte() }
            Opcode.INT_TO_CHAR -> conv(frame, r, "C") { ci(it).toChar() }
            Opcode.INT_TO_SHORT -> conv(frame, r, "S") { ci(it).toShort() }

            Opcode.NEW_INSTANCE -> {
                val desc = (insn.ref as TypeRef).desc
                vm.ensureClinit(desc)
                frame.set(r[0], if (vm.source.classInfo(desc) != null) DvmObject(desc) else UninitHost(desc))
            }
            Opcode.NEW_ARRAY -> {
                val desc = (insn.ref as TypeRef).desc
                val len = frame.get(r[1])
                frame.set(r[0], if (len.unk()) UnknownVal(desc) else newArray(desc, ci(len)))
            }
            Opcode.ARRAY_LENGTH -> {
                val a = frame.get(r[1])
                frame.set(r[0], if (a == null || a.unk() || !a.javaClass.isArray) UnknownVal("I") else java.lang.reflect.Array.getLength(a))
            }
            Opcode.AGET, Opcode.AGET_BOOLEAN, Opcode.AGET_BYTE, Opcode.AGET_BYTE_BOOLEAN, Opcode.AGET_CHAR,
            Opcode.AGET_SHORT, Opcode.AGET_OBJECT, Opcode.AGET_WIDE -> {
                val a = frame.get(r[1]); val ix = frame.get(r[2])
                val v = if (a == null || a.unk() || ix.unk()) UnknownVal(agetType(a, insn.opcode))
                else runCatching { java.lang.reflect.Array.get(a, ci(ix)) }.getOrElse { throw VmAbort("aget") }
                if (insn.opcode == Opcode.AGET_WIDE) frame.setWide(r[0], v) else frame.set(r[0], v)
            }
            Opcode.APUT, Opcode.APUT_BOOLEAN, Opcode.APUT_BYTE, Opcode.APUT_BYTE_BOOLEAN, Opcode.APUT_CHAR,
            Opcode.APUT_SHORT, Opcode.APUT_OBJECT, Opcode.APUT_WIDE -> {
                val a = frame.get(r[1]); val ix = frame.get(r[2]); val v = frame.get(r[0])
                if (a != null && !a.unk()) {
                    if (ix.unk()) frame.replace(a, UnknownVal(valueType(a)))
                    else runCatching { aput(a, ci(ix), v) }.getOrElse { throw VmAbort("aput") }
                }
            }
            Opcode.FILL_ARRAY_DATA -> {
                val a = frame.get(r[0]); val data = (insn.payload as? ArrayPayload)?.data
                if (a != null && !a.unk() && data != null) fillArray(a, data)
            }

            Opcode.IGET -> { val fr = insn.ref as FieldRef; val o = frame.get(r[1]); fieldSet(frame, r[0], if (o is DvmObject) o.fields[key(fr)] else UnknownVal(fr.type), fr.type) }
            Opcode.IPUT -> { val fr = insn.ref as FieldRef; val o = frame.get(r[1]); if (o is DvmObject) o.fields[key(fr)] = retype(frame.get(r[0]), fr.type) }
            Opcode.SGET -> {
                val fr = insn.ref as FieldRef; vm.ensureClinit(fr.declClass)
                fieldSet(frame, r[0], when {
                    vm.source.classInfo(fr.declClass) != null -> vm.staticsOf(fr.declClass)[key(fr)] ?: UnknownVal(fr.type)
                    else -> vm.hostStaticField(fr.declClass, fr.name).let { if (it !== NotHandled) it else UnknownVal(fr.type) }
                }, fr.type)
            }
            Opcode.SPUT -> {
                val fr = insn.ref as FieldRef; vm.ensureClinit(fr.declClass)
                if (vm.source.classInfo(fr.declClass) != null) vm.staticsOf(fr.declClass)[key(fr)] = retype(frame.get(r[0]), fr.type)
            }

            Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> {
                val desc = (insn.ref as TypeRef).desc
                val vals = r.map { frame.get(it) }
                frame.result = if (vals.any { it.unk() }) UnknownVal(desc) else filledArray(desc, vals)
                frame.resultType = desc
            }
            Opcode.CONST_METHOD_HANDLE -> frame.set(r[0], UnknownVal("Ljava/lang/invoke/MethodHandle;"))
            Opcode.CONST_METHOD_TYPE -> frame.set(r[0], UnknownVal("Ljava/lang/invoke/MethodType;"))
            Opcode.INVOKE_POLYMORPHIC, Opcode.INVOKE_POLYMORPHIC_RANGE -> {
                val ref = insn.ref as MethodRef
                frame.result = UnknownVal(ref.returnType); frame.resultType = ref.returnType
            }

            Opcode.MONITOR_ENTER, Opcode.MONITOR_EXIT -> {}
            Opcode.INSTANCE_OF -> {
                val o = frame.get(r[1]); val ct = (insn.ref as TypeRef).desc
                frame.set(r[0], when {
                    o.unk() -> UnknownVal("I")
                    o == null -> 0
                    else -> valueType(o)?.let { if (isAssignable(it, ct)) 1 else 0 } ?: UnknownVal("I")
                })
            }
            else -> throw VmAbort("unimplemented ${insn.opcode}")
        }
    }

    /**
     * Abstractly execute one instruction in place on [frame].
     *
     * Unknown operands produce unknown results instead of aborting, and undecidable branches report every
     * successor. Calls go through [resolver], or yield unknown values when it is null; heap accesses go through
     * [heap], or through the VM's concrete state when it is null.
     */
    fun absStep(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int, resolver: CallResolver? = null, heap: AbsHeap? = null): AbsResult {
        val res = absStepInner(method, insn, frame, pc, resolver, heap)
        if (insn.resultReg >= 0 && !res.returns) applyResultReg(insn, frame)
        return res
    }

    private fun applyResultReg(insn: DalvikInsn, frame: Frame) {
        val t = frame.resultType ?: return
        if (t == "V") return
        if (t == "J" || t == "D") frame.setWide(insn.resultReg, frame.result) else frame.set(insn.resultReg, retype(frame.result, t))
        frame.result = null; frame.resultType = null
    }

    private fun absStepInner(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int, resolver: CallResolver?, heap: AbsHeap?): AbsResult {
        val r = insn.regs
        return when (insn.opcode) {
            Opcode.RETURN -> AbsResult(EMPTY_INTS, retype(frame.get(r[0]), method.ref.returnType), true)
            Opcode.RETURN_VOID -> AbsResult(EMPTY_INTS, null, true)
            Opcode.GOTO -> AbsResult(intArrayOf(insn.target), null, false)
            Opcode.IF_EQ -> absBranch(method, insn, pc, eqAbs(frame.get(r[0]), frame.get(r[1])))
            Opcode.IF_NE -> absBranch(method, insn, pc, eqAbs(frame.get(r[0]), frame.get(r[1]))?.not())
            Opcode.IF_LT -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a < b })
            Opcode.IF_GE -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a >= b })
            Opcode.IF_GT -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a > b })
            Opcode.IF_LE -> absBranch(method, insn, pc, relAbs(frame.get(r[0]), frame.get(r[1])) { a, b -> a <= b })
            Opcode.IF_EQZ -> absBranch(method, insn, pc, zAbs(frame.get(r[0])))
            Opcode.IF_NEZ -> absBranch(method, insn, pc, zAbs(frame.get(r[0]))?.not())
            Opcode.IF_LTZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it < 0 })
            Opcode.IF_GEZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it >= 0 })
            Opcode.IF_GTZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it > 0 })
            Opcode.IF_LEZ -> absBranch(method, insn, pc, relZAbs(frame.get(r[0])) { it <= 0 })
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> absSwitch(method, insn, frame, pc)
            Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
            Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
            Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> {
                val ref = insn.ref as MethodRef
                if (resolver != null) {
                    val (res, rt) = resolver.resolve(insn, frame)
                    frame.result = res; frame.resultType = rt
                } else {
                    frame.result = UnknownVal(ref.returnType); frame.resultType = ref.returnType
                }
                fallResult(method, pc)
            }
            Opcode.DIV_INT -> { absDivInt(frame, r) { a, b -> a / b }; fallResult(method, pc) }
            Opcode.REM_INT -> { absDivInt(frame, r) { a, b -> a % b }; fallResult(method, pc) }
            Opcode.DIV_INT_LIT -> { absDivIntLit(frame, r, insn.literal.toInt()) { a, b -> a / b }; fallResult(method, pc) }
            Opcode.REM_INT_LIT -> { absDivIntLit(frame, r, insn.literal.toInt()) { a, b -> a % b }; fallResult(method, pc) }
            Opcode.DIV_LONG -> { absDivLong(frame, r) { a, b -> a / b }; fallResult(method, pc) }
            Opcode.REM_LONG -> { absDivLong(frame, r) { a, b -> a % b }; fallResult(method, pc) }
            Opcode.THROW -> AbsResult(EMPTY_INTS, null, false)
            Opcode.INVOKE_CUSTOM, Opcode.INVOKE_CUSTOM_RANGE -> { invokeCustom(insn, frame); fallResult(method, pc) }
            Opcode.CHECK_CAST -> fallResult(method, pc)
            Opcode.NEW_INSTANCE -> {
                if (heap != null) frame.set(r[0], heap.newInstance(siteOf(method, insn), (insn.ref as TypeRef).desc)) else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.IGET -> {
                if (heap != null) { val fr = insn.ref as FieldRef; fieldSet(frame, r[0], heap.iget(frame.get(r[1]), fr.key, fr.type), fr.type) } else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.IPUT -> {
                if (heap != null) { val fr = insn.ref as FieldRef; heap.iput(frame.get(r[1]), fr.key, retype(frame.get(r[0]), fr.type)) } else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.SGET -> {
                val fr = insn.ref as FieldRef
                if (heap != null && vm.source.classInfo(fr.declClass) != null) fieldSet(frame, r[0], heap.sget(fr.declClass, fr.key, fr.type), fr.type) else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            Opcode.SPUT -> {
                val fr = insn.ref as FieldRef
                if (heap != null && vm.source.classInfo(fr.declClass) != null) heap.sput(fr.declClass, fr.key, retype(frame.get(r[0]), fr.type)) else dataTransfer(insn, frame)
                fallResult(method, pc)
            }
            else -> {
                val ok = runCatching { dataTransfer(insn, frame) }.isSuccess
                if (!ok) { frame.result = UnknownVal(null); frame.resultType = null; if (r.isNotEmpty()) frame.set(r[0], UnknownVal(null)) }
                fallResult(method, pc)
            }
        }
    }

    private fun siteOf(method: DexMethod, insn: DalvikInsn) = "${method.declClass}#${method.ref.shortId}@${insn.offset}"

    private fun fallResult(method: DexMethod, pc: Int) = AbsResult(fall(method, pc), null, false)

    private fun fall(method: DexMethod, pc: Int): IntArray =
        fallCache.getOrPut(method) {
            val n = method.insns.size
            Array(n) { i -> if (i + 1 < n) intArrayOf(method.insns[i + 1].offset) else EMPTY_INTS }
        }[pc]

    private fun absBranch(method: DexMethod, insn: DalvikInsn, pc: Int, cond: Boolean?): AbsResult = when (cond) {
        true -> AbsResult(intArrayOf(insn.target), null, false)
        false -> AbsResult(fall(method, pc), null, false)
        null -> AbsResult(fall(method, pc) + insn.target, null, false)
    }

    private fun absSwitch(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int): AbsResult {
        val sw = insn.payload as? SwitchPayload ?: return fallResult(method, pc)
        val sel = frame.get(insn.regs[0])
        if (sel.unk()) return AbsResult(fall(method, pc) + IntArray(sw.targets.size) { insn.offset + sw.targets[it] }, null, false)
        val k = sw.keys.indexOf(ci(sel))
        return if (k < 0) fallResult(method, pc) else AbsResult(intArrayOf(insn.offset + sw.targets[k]), null, false)
    }

    private fun eqAbs(a: Any?, b: Any?): Boolean? = if (a.unk() || b.unk()) null else eq(a, b)
    private fun zAbs(v: Any?): Boolean? = if (v.unk()) null else isZero(v)
    private fun relAbs(a: Any?, b: Any?, op: (Int, Int) -> Boolean): Boolean? = if (a.unk() || b.unk()) null else op(ci(a), ci(b))
    private fun relZAbs(v: Any?, op: (Int) -> Boolean): Boolean? = if (v.unk()) null else op(ci(v))

    private fun absDivInt(frame: Frame, r: IntArray, op: (Int, Int) -> Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk() || ci(b) == 0) UnknownVal("I") else op(ci(a), ci(b)))
    }
    private fun absDivIntLit(frame: Frame, r: IntArray, lit: Int, op: (Int, Int) -> Int) {
        val a = frame.get(r[1])
        frame.set(r[0], if (a.unk() || lit == 0) UnknownVal("I") else op(ci(a), lit))
    }
    private fun absDivLong(frame: Frame, r: IntArray, op: (Long, Long) -> Long) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk() || cl(b) == 0L) UnknownVal("J") else op(cl(a), cl(b)))
    }

    private fun invoke(method: DexMethod, insn: DalvikInsn, frame: Frame, pc: Int, hasReceiver: Boolean, virtual: Boolean, superCall: Boolean): Step {
        val ref = insn.ref as MethodRef
        if (!hasReceiver) vm.ensureClinit(ref.declClass)
        val (recv, args) = gatherArgs(insn, frame, ref, hasReceiver)
        vm.hooks?.interceptors("${ref.declClass}->${ref.shortId}")?.takeIf { it.isNotEmpty() }?.let { hooks ->
            val call = HookCall("${ref.declClass}->${ref.shortId}", recv, args)
            for (h in hooks) h.onInvoke(call)
            if (call.replaced) {
                frame.result = retype(call.result, ref.returnType); frame.resultType = ref.returnType
                return Next(pc + 1)
            }
        }
        tryReflect(ref, recv, args)?.let { (res, rt) ->
            frame.result = res; frame.resultType = rt; return Next(pc + 1)
        }
        if (ref.name == "<init>" && recv is UninitHost) {
            frame.replace(recv, vm.hostExec.construct(recv.type, ref, args))
            return Next(pc + 1)
        }
        val target = when {
            superCall -> resolveVirtual(vm.source.classInfo(ref.declClass)?.superType, ref.shortId)
            virtual -> resolveVirtual((recv as? DvmObject)?.type ?: ref.declClass, ref.shortId)
            !hasReceiver -> resolveVirtual(ref.declClass, ref.shortId)
            else -> vm.source.method(ref.declClass, ref.shortId)
        }
        if (target == null && vm.nativeBridge != null && vm.source.isNative(ref.declClass, ref.shortId)) {
            val sig = "(${ref.argTypes.joinToString("")})${ref.returnType}"
            val nr = vm.nativeBridge.call(ref.declClass.removePrefix("L").removeSuffix(";"), ref.name, sig, args, recv)
            if (nr !== NotHandled) { frame.result = nr; frame.resultType = ref.returnType; return Next(pc + 1) }
        }
        val result = try {
            when {
                target != null -> vm.call(target, args, recv)
                hasReceiver -> vm.hostExec.invokeInstance(ref, recv, args)
                else -> vm.hostExec.invokeStatic(ref, args)
            }
        } catch (t: DvmThrowable) {
            return routeThrow(method, insn.offset, t, frame)
        } catch (e: StubNotImplemented) {
            UnknownVal(ref.returnType)
        }
        frame.result = result
        frame.resultType = ref.returnType
        return Next(pc + 1)
    }

    /**
     * Find the implementation of [shortId] starting at class [startType] and walking up the superclass chain.
     */
    fun resolveVirtualFor(startType: String?, shortId: String): DexMethod? = resolveVirtual(startType, shortId)

    /**
     * Read an invoke's receiver and arguments from [frame].
     */
    fun gatherArgs(insn: DalvikInsn, frame: Frame, ref: MethodRef, hasReceiver: Boolean): Pair<Any?, MutableList<Any?>> {
        val regs = insn.regs
        var i = 0
        val recv = if (hasReceiver) frame.get(regs[i++]) else null
        val args = ArrayList<Any?>(ref.argTypes.size)
        for (t in ref.argTypes) {
            args.add(frame.get(regs[i]))
            i += if (t == "J" || t == "D") 2 else 1
        }
        return recv to args
    }

    private fun invokeCustom(insn: DalvikInsn, frame: Frame) {
        val cs = insn.ref as? CallSiteRef
        if (cs == null) { frame.result = UnknownVal(null); frame.resultType = null; return }
        frame.result = evalCallSite(cs, gatherCustomArgs(insn, frame, cs.argTypes))
        frame.resultType = cs.returnType
    }

    private fun gatherCustomArgs(insn: DalvikInsn, frame: Frame, argTypes: List<String>): List<Any?> {
        val regs = insn.regs
        var i = 0
        val args = ArrayList<Any?>(argTypes.size)
        for (t in argTypes) {
            args.add(frame.get(regs.getOrNull(i) ?: return args))
            i += if (t == "J" || t == "D") 2 else 1
        }
        return args
    }

    private fun evalCallSite(cs: CallSiteRef, args: List<Any?>): Any? = when (cs.name) {
        "makeConcat" -> if (args.any { !concatable(it) }) UnknownVal("Ljava/lang/String;") else args.joinToString("") { concatStr(it) }
        "makeConcatWithConstants" -> {
            val recipe = cs.recipe
            if (recipe == null) UnknownVal("Ljava/lang/String;") else weaveRecipe(recipe, args, cs.constants)
        }
        else -> UnknownVal(cs.returnType)
    }

    private fun weaveRecipe(recipe: String, args: List<Any?>, constants: List<Any?>): Any? {
        val sb = StringBuilder(recipe.length)
        var ai = 0; var ci = 0
        for (c in recipe) when (c.code) {
            1 -> { val a = args.getOrNull(ai++); if (!concatable(a)) return UnknownVal("Ljava/lang/String;"); sb.append(concatStr(a)) }
            2 -> sb.append(concatStr(constants.getOrNull(ci++)))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun concatable(v: Any?): Boolean = v !is UnknownVal && v !is DvmObject && v !is UninitHost && v !is DvmClass && v !is DvmMethodHandle
    private fun concatStr(v: Any?): String = if (v == null) "null" else v.toString()

    private fun resolveVirtual(startType: String?, shortId: String): DexMethod? {
        var cur = startType
        while (cur != null) {
            vm.source.method(cur, shortId)?.let { return it }
            cur = vm.source.classInfo(cur)?.superType
        }
        return null
    }

    /**
     * Emulate a `java.lang.reflect` or `Class` call on emulated values.
     *
     * @param allowInvoke whether `Method.invoke` may execute the target
     * @return the result and its type, or null if [ref] is not a handled reflective call
     */
    fun tryReflect(ref: MethodRef, recv: Any?, args: List<Any?>, allowInvoke: Boolean = true): Pair<Any?, String?>? {
        when (ref.declClass) {
            "Ljava/lang/Class;" -> when (ref.name) {
                "forName" -> (args.getOrNull(0) as? String)?.let {
                    return DvmClass("L" + it.replace('.', '/') + ";") to "Ljava/lang/Class;"
                }
                "getDeclaredMethod", "getMethod" -> {
                    val cls = recv as? DvmClass ?: return null
                    val name = args.getOrNull(0) as? String ?: return null
                    val params = (args.getOrNull(1) as? Array<*>)?.map { classDesc(it) } ?: emptyList()
                    return resolveMethodHandle(cls.desc, name, params) to "Ljava/lang/reflect/Method;"
                }
                "getDeclaredField", "getField" -> {
                    val cls = recv as? DvmClass ?: return null
                    val name = args.getOrNull(0) as? String ?: return null
                    return resolveFieldHandle(cls.desc, name) to "Ljava/lang/reflect/Field;"
                }
                "getDeclaredFields", "getFields" -> {
                    val cls = recv as? DvmClass ?: return null
                    val ci = vm.source.classInfo(cls.desc)
                    if (ci != null) return ci.fields.map { DvmField(it.ref, it.isStatic) as Any? }.toTypedArray() to "[Ljava/lang/reflect/Field;"
                    val hf = runCatching {
                        hostClass(cls.desc).let { if (ref.name == "getFields") it.fields else it.declaredFields }
                            .map { DvmField(FieldRef(cls.desc, it.name, classDesc(it.type)), java.lang.reflect.Modifier.isStatic(it.modifiers)) as Any? }.toTypedArray()
                    }.getOrNull() ?: return null
                    return hf to "[Ljava/lang/reflect/Field;"
                }
                "getName", "getCanonicalName" -> (recv as? DvmClass)?.let {
                    return it.desc.removePrefix("L").removeSuffix(";").replace('/', '.') to "Ljava/lang/String;"
                }
                "getSimpleName" -> (recv as? DvmClass)?.let {
                    return it.desc.removePrefix("L").removeSuffix(";").substringAfterLast('/') to "Ljava/lang/String;"
                }
            }
            "Ljava/lang/reflect/Method;" -> if (allowInvoke && ref.name == "invoke") {
                val h = recv as? DvmMethodHandle ?: return null
                val rt = h.dexMethod?.ref?.returnType ?: h.hostMethod?.let { classDesc(it.returnType) } ?: "Ljava/lang/Object;"
                return invokeHandle(h, args.getOrNull(0), args.getOrNull(1)) to rt
            }
            "Ljava/lang/reflect/Field;" -> {
                val f = recv as? DvmField ?: return null
                when (ref.name) {
                    "getName" -> return f.ref.name to "Ljava/lang/String;"
                    "getType" -> return DvmClass(f.ref.type) to "Ljava/lang/Class;"
                    "setAccessible" -> return null to "V"
                    "get" -> {
                        if (f.isStatic) { vm.ensureClinit(f.ref.declClass); return (vm.staticsOf(f.ref.declClass)[f.ref.key] ?: UnknownVal(f.ref.type)) to f.ref.type }
                        val obj = args.getOrNull(0) as? DvmObject ?: return null
                        return (obj.fields[f.ref.key] ?: UnknownVal(f.ref.type)) to f.ref.type
                    }
                    "set" -> {
                        val v = retype(args.getOrNull(1), f.ref.type)
                        if (f.isStatic) { vm.ensureClinit(f.ref.declClass); vm.staticsOf(f.ref.declClass)[f.ref.key] = v }
                        else (args.getOrNull(0) as? DvmObject)?.let { it.fields[f.ref.key] = v }
                        return null to "V"
                    }
                }
            }
            "Ljava/lang/Object;" -> if (ref.name == "getClass") {
                val t = valueType(recv) ?: return null
                return DvmClass(t) to "Ljava/lang/Class;"
            }
        }
        return null
    }

    private fun classDesc(c: Any?): String = when (c) {
        is DvmClass -> c.desc
        is Class<*> -> when {
            c == Integer.TYPE -> "I"; c == java.lang.Long.TYPE -> "J"; c == java.lang.Boolean.TYPE -> "Z"
            c == java.lang.Byte.TYPE -> "B"; c == Character.TYPE -> "C"; c == java.lang.Short.TYPE -> "S"
            c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"; c == Void.TYPE -> "V"
            c.isArray -> c.name.replace('.', '/')
            else -> "L" + c.name.replace('.', '/') + ";"
        }
        else -> "Ljava/lang/Object;"
    }

    private fun resolveFieldHandle(owner: String, name: String): Any? {
        vm.source.classInfo(owner)?.fields?.firstOrNull { it.ref.name == name }?.let { return DvmField(it.ref, it.isStatic) }
        val hostF = runCatching { hostClass(owner).getDeclaredField(name) }.getOrNull()
        if (hostF != null) return DvmField(FieldRef(owner, name, classDesc(hostF.type)), java.lang.reflect.Modifier.isStatic(hostF.modifiers))
        return DvmField(FieldRef(owner, name, "Ljava/lang/Object;"), false)
    }

    private fun resolveMethodHandle(owner: String, name: String, params: List<String>): Any? {
        if (vm.source.classInfo(owner) != null) {
            val cands = vm.source.methodsByName(owner, name)
            val m = cands.firstOrNull { it.ref.argTypes == params } ?: cands.firstOrNull { it.ref.argTypes.size == params.size }
            if (m != null) return DvmMethodHandle(m, null)
        }
        val hostM = runCatching { hostClass(owner).getDeclaredMethod(name, *params.map { hostClass(it) }.toTypedArray()) }.getOrNull()
        if (hostM != null) return DvmMethodHandle(null, hostM)
        return DvmMethodHandle(null, null, MethodRef(owner, name, params, "Ljava/lang/Object;"))
    }

    private fun invokeHandle(h: DvmMethodHandle, target: Any?, argArr: Any?): Any? {
        val argList = (argArr as? Array<*>)?.toList() ?: if (argArr == null) emptyList() else return UnknownVal("Ljava/lang/Object;")
        h.dexMethod?.let { return vm.call(it, argList, if (it.isStatic) null else target) }
        h.hostMethod?.let { m -> return runCatching { m.invoke(target, *argList.toTypedArray()) }.getOrElse { UnknownVal("Ljava/lang/Object;") } }
        return UnknownVal("Ljava/lang/Object;")
    }

    private fun routeThrow(method: DexMethod, offset: Int, t: DvmThrowable, frame: Frame): Step {
        val h = findHandler(method, offset, t.type) ?: throw t
        val ti = method.offsetToIndex[h.offset] ?: throw t
        frame.pendingException = t
        return Next(ti)
    }

    private fun findHandler(method: DexMethod, offset: Int, throwType: String): Handler? {
        for (tb in method.tries) {
            if (offset in tb.start..tb.end) {
                for (h in tb.handlers) if (h.type == null || isAssignable(throwType, h.type)) return h
            }
        }
        return null
    }

    private fun isAssignable(sub: String, sup: String): Boolean {
        if (sub == sup || sup == "Ljava/lang/Object;") return true
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(sub)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!seen.add(cur)) continue
            if (cur == sup) return true
            val info = vm.source.classInfo(cur)
            if (info == null) {
                if (runCatching { hostClass(sup).isAssignableFrom(hostClass(cur)) }.getOrDefault(false)) return true
                continue
            }
            info.superType?.let { stack.addLast(it) }
            info.interfaces.forEach { stack.addLast(it) }
        }
        return false
    }

    private fun valueType(o: Any?): String? = when (o) {
        is DvmObject -> o.type
        is DvmClass -> "Ljava/lang/Class;"
        is DvmMethodHandle -> "Ljava/lang/reflect/Method;"
        is DvmField -> "Ljava/lang/reflect/Field;"
        is String -> "Ljava/lang/String;"
        is BooleanArray -> "[Z"; is ByteArray -> "[B"; is CharArray -> "[C"; is ShortArray -> "[S"
        is IntArray -> "[I"; is LongArray -> "[J"; is FloatArray -> "[F"; is DoubleArray -> "[D"
        is Array<*> -> "[Ljava/lang/Object;"
        is UninitHost, is UnknownVal, null -> null
        else -> runCatching { "L" + o.javaClass.name.replace('.', '/') + ";" }.getOrNull()
    }

    private fun asThrowable(v: Any?): DvmThrowable = when (v) {
        is DvmThrowable -> v
        is DvmObject -> DvmThrowable(v.type, v.fields["detailMessage"] as? String, v)
        is UninitHost -> DvmThrowable(v.type, null, v)
        is Throwable -> DvmThrowable("L" + v.javaClass.name.replace('.', '/') + ";", v.message, v)
        else -> DvmThrowable("Ljava/lang/Throwable;", v?.toString())
    }

    private fun branch(method: DexMethod, insn: DalvikInsn, pc: Int, taken: Boolean): Step =
        if (taken) Next(idx(method, insn.target)) else Next(pc + 1)

    private fun idx(method: DexMethod, offset: Int): Int = method.offsetToIndex[offset] ?: throw VmAbort("bad target $offset")

    private fun Any?.unk(): Boolean = this is UnknownVal
    private fun key(fr: FieldRef) = fr.key

    private fun agetType(a: Any?, op: Opcode): String {
        if (a != null && !a.unk()) valueType(a)?.takeIf { it.startsWith("[") }?.let { return it.substring(1) }
        return when (op) {
            Opcode.AGET_OBJECT -> "Ljava/lang/Object;"; Opcode.AGET_WIDE -> "J"
            Opcode.AGET_BOOLEAN -> "Z"; Opcode.AGET_BYTE, Opcode.AGET_BYTE_BOOLEAN -> "B"
            Opcode.AGET_CHAR -> "C"; Opcode.AGET_SHORT -> "S"; else -> "I"
        }
    }

    private fun aReg(r: IntArray) = if (r.size >= 3) r[1] else r[0]
    private fun bReg(r: IntArray) = if (r.size >= 3) r[2] else r[1]

    private fun binInt(frame: Frame, r: IntArray, op: (Int, Int) -> Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else op(ci(a), ci(b)))
    }
    private fun divInt(method: DexMethod, insn: DalvikInsn, pc: Int, frame: Frame, op: (Int, Int) -> Int): Step {
        val r = insn.regs; val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        if (a.unk() || b.unk()) { frame.set(r[0], UnknownVal("I")); return Next(pc + 1) }
        if (ci(b) == 0) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ArithmeticException;", "/ by zero"), frame)
        frame.set(r[0], op(ci(a), ci(b))); return Next(pc + 1)
    }
    private fun divIntLit(method: DexMethod, insn: DalvikInsn, pc: Int, frame: Frame, lit: Int, op: (Int, Int) -> Int): Step {
        val r = insn.regs; val a = frame.get(r[1])
        if (a.unk()) { frame.set(r[0], UnknownVal("I")); return Next(pc + 1) }
        if (lit == 0) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ArithmeticException;", "/ by zero"), frame)
        frame.set(r[0], op(ci(a), lit)); return Next(pc + 1)
    }
    private fun divLong(method: DexMethod, insn: DalvikInsn, pc: Int, frame: Frame, op: (Long, Long) -> Long): Step {
        val r = insn.regs; val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        if (a.unk() || b.unk()) { frame.setWide(r[0], UnknownVal("J")); return Next(pc + 1) }
        if (cl(b) == 0L) return routeThrow(method, insn.offset, DvmThrowable("Ljava/lang/ArithmeticException;", "/ by zero"), frame)
        frame.setWide(r[0], op(cl(a), cl(b))); return Next(pc + 1)
    }
    private fun litInt(frame: Frame, r: IntArray, lit: Int, op: (Int, Int) -> Int) {
        val a = frame.get(r[1])
        frame.set(r[0], if (a.unk()) UnknownVal("I") else op(ci(a), lit))
    }
    private fun unInt(frame: Frame, r: IntArray, op: (Int) -> Int) {
        val a = frame.get(r[1]); frame.set(r[0], if (a.unk()) UnknownVal("I") else op(ci(a)))
    }
    private fun binLong(frame: Frame, r: IntArray, op: (Long, Long) -> Long) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk()) UnknownVal("J") else op(cl(a), cl(b)))
    }
    private fun binLongShift(frame: Frame, r: IntArray, op: (Long, Int) -> Long) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk()) UnknownVal("J") else op(cl(a), ci(b) and 0x3f))
    }
    private fun binFloat(frame: Frame, r: IntArray, op: (Float, Float) -> Float) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("F") else op(cf(a), cf(b)))
    }
    private fun binDouble(frame: Frame, r: IntArray, op: (Double, Double) -> Double) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.setWide(r[0], if (a.unk() || b.unk()) UnknownVal("D") else op(cd(a), cd(b)))
    }
    private fun cmpF(frame: Frame, r: IntArray, nan: Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else cmpFloat(cf(a), cf(b), nan))
    }
    private fun cmpD(frame: Frame, r: IntArray, nan: Int) {
        val a = frame.get(aReg(r)); val b = frame.get(bReg(r))
        frame.set(r[0], if (a.unk() || b.unk()) UnknownVal("I") else cmpDouble(cd(a), cd(b), nan))
    }
    private fun conv(frame: Frame, r: IntArray, type: String, op: (Any?) -> Any) {
        val a = frame.get(r[1])
        val v = if (a.unk()) UnknownVal(type) else op(a)
        if (type == "J" || type == "D") frame.setWide(r[0], v) else frame.set(r[0], v)
    }

    private fun cmpFloat(a: Float, b: Float, nan: Int) = if (a.isNaN() || b.isNaN()) nan else a.compareTo(b).coerceIn(-1, 1)
    private fun cmpDouble(a: Double, b: Double, nan: Int) = if (a.isNaN() || b.isNaN()) nan else a.compareTo(b).coerceIn(-1, 1)

    private fun isNumeric(v: Any?) = v is Int || v is Long || v is Boolean || v is Char || v is Byte || v is Short
    private fun isZero(v: Any?): Boolean { if (v.unk()) throw VmAbort("unknown branch"); return if (isNumeric(v)) ci(v) == 0 else v == null }
    private fun ci2(v: Any?): Int { if (v.unk()) throw VmAbort("unknown branch"); return ci(v) }
    private fun eq(a: Any?, b: Any?): Boolean {
        if (a.unk() || b.unk()) throw VmAbort("unknown branch")
        return if (isNumeric(a) && isNumeric(b)) ci(a) == ci(b) else a === b
    }

    private fun ci(v: Any?): Int = when (v) { is Int -> v; is Boolean -> if (v) 1 else 0; is Char -> v.code; is Byte -> v.toInt(); is Short -> v.toInt(); is Long -> v.toInt(); else -> 0 }
    private fun cl(v: Any?): Long = when (v) { is Long -> v; is Int -> v.toLong(); else -> ci(v).toLong() }
    private fun cf(v: Any?): Float = when (v) { is Float -> v; is Double -> v.toFloat(); is Int -> Float.fromBits(v); is Long -> v.toFloat(); else -> ci(v).toFloat() }
    private fun cd(v: Any?): Double = when (v) { is Double -> v; is Float -> v.toDouble(); is Long -> Double.fromBits(v); else -> ci(v).toDouble() }

    private fun fieldSet(frame: Frame, dest: Int, raw: Any?, t: String) {
        val v = retype(raw, t)
        if (t == "J" || t == "D") frame.setWide(dest, v) else frame.set(dest, v)
    }

    private fun retype(v: Any?, t: String?): Any? = when {
        v.unk() || v == null || !isNumeric(v) -> v
        t == "C" -> ci(v).toChar(); t == "B" -> ci(v).toByte(); t == "S" -> ci(v).toShort(); t == "Z" -> ci(v) != 0
        t == "I" -> ci(v); t == "J" -> cl(v); t == "F" -> cf(v); t == "D" -> cd(v)
        else -> v
    }

    private fun filledArray(desc: String, vals: List<Any?>): Any {
        val a = newArray(desc, vals.size)
        for (i in vals.indices) aput(a, i, vals[i])
        return a
    }

    private fun newArray(desc: String, len: Int): Any = when (desc) {
        "[I" -> IntArray(len); "[J" -> LongArray(len); "[B" -> ByteArray(len); "[C" -> CharArray(len)
        "[S" -> ShortArray(len); "[Z" -> BooleanArray(len); "[F" -> FloatArray(len); "[D" -> DoubleArray(len)
        else -> arrayOfNulls<Any?>(len)
    }

    @Suppress("UNCHECKED_CAST")
    private fun aput(a: Any, ix: Int, v: Any?) {
        when (a) {
            is IntArray -> a[ix] = ci(v); is ByteArray -> a[ix] = ci(v).toByte(); is CharArray -> a[ix] = ci(v).toChar()
            is ShortArray -> a[ix] = ci(v).toShort(); is BooleanArray -> a[ix] = ci(v) != 0; is LongArray -> a[ix] = cl(v)
            is FloatArray -> a[ix] = cf(v); is DoubleArray -> a[ix] = cd(v)
            else -> (a as Array<Any?>)[ix] = v
        }
    }

    private fun fillArray(a: Any, data: Any) {
        val n = java.lang.reflect.Array.getLength(data)
        for (k in 0 until n) {
            val x = java.lang.reflect.Array.get(data, k) as Number
            when (a) {
                is IntArray -> a[k] = x.toInt(); is ByteArray -> a[k] = x.toByte(); is ShortArray -> a[k] = x.toShort()
                is CharArray -> a[k] = x.toInt().toChar(); is BooleanArray -> a[k] = x.toInt() != 0; is LongArray -> a[k] = x.toLong()
                is FloatArray -> a[k] = Float.fromBits(x.toInt()); is DoubleArray -> a[k] = Double.fromBits(x.toLong())
            }
        }
    }
}
