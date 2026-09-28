package jadx.plugins.emu.exec.model

/**
 * A constant-pool item referenced by an instruction.
 */
sealed interface InsnRef

/**
 * Reference to a method.
 *
 * @property declClass descriptor of the class the reference names (not necessarily where the method is defined)
 * @property name method name
 * @property argTypes parameter type descriptors
 * @property returnType return type descriptor
 * @property shortId `name(argTypes)returnType`, the key used by [jadx.plugins.emu.exec.MethodSource]
 */
data class MethodRef(
    val declClass: String,
    val name: String,
    val argTypes: List<String>,
    val returnType: String,
) : InsnRef {
    val shortId: String = buildString {
        append(name); append('(')
        argTypes.forEach { append(it) }
        append(')'); append(returnType)
    }
}

/**
 * Reference to a field.
 *
 * @property declClass descriptor of the declaring class
 * @property name field name
 * @property type field type descriptor
 * @property key `declClass.name`, the key used for static and instance field storage
 */
data class FieldRef(val declClass: String, val name: String, val type: String) : InsnRef {
    val key: String = "$declClass.$name"
}

/**
 * Reference to a type (`const-class`, `new-instance`, `check-cast`, ...).
 *
 * @property desc type descriptor
 */
data class TypeRef(val desc: String) : InsnRef

/**
 * A string constant (`const-string`).
 */
data class StringRef(val value: String) : InsnRef

/**
 * An `invoke-custom` call site.
 *
 * @property name the name passed to the bootstrap method
 * @property recipe the `StringConcatFactory.makeConcatWithConstants` recipe, if this site is a string concatenation
 * @property constants extra bootstrap constants following the recipe
 * @property argTypes dynamic argument type descriptors
 * @property returnType return type descriptor
 */
data class CallSiteRef(
    val name: String,
    val recipe: String?,
    val constants: List<Any?>,
    val argTypes: List<String>,
    val returnType: String,
) : InsnRef
