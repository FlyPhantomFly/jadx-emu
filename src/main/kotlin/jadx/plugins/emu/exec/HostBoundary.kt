package jadx.plugins.emu.exec

/**
 * Policy controlling which JVM classes emulated code may execute directly on the host.
 *
 * Only classes in the allow list are reachable; within them, methods with side effects on the
 * process are always blocked and, unless [allowNondeterministic] is set, so are methods whose
 * results vary between runs. Blocked calls evaluate to unknown values.
 *
 * @param allow descriptors of host classes that may be invoked; defaults to [DEFAULT_ALLOW].
 *   An empty set disables host execution entirely
 * @param blocked additional `Lcls;->name` signatures to deny
 * @param allowNondeterministic permit [NONDETERMINISTIC] methods (time, random, identity hashes)
 */
class HostBoundary(
    private val allow: Set<String> = DEFAULT_ALLOW,
    private val blocked: Set<String> = emptySet(),
    private val allowNondeterministic: Boolean = false,
) {

    /**
     * Whether methods of [declClass] may run on the host at all.
     */
    fun canHandle(declClass: String): Boolean = declClass in allow

    /**
     * Whether `declClass->name` is denied even though its class is allowed.
     */
    fun isBlocked(declClass: String, name: String): Boolean {
        val sig = "$declClass->$name"
        return sig in UNSAFE_METHODS || sig in blocked || (!allowNondeterministic && sig in NONDETERMINISTIC)
    }

    companion object {
        /**
         * Pure, deterministic JDK and Android classes that are safe to execute on the host.
         */
        val DEFAULT_ALLOW = setOf(
            "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/StringBuilder;", "Ljava/lang/StringBuffer;",
            "Ljava/lang/CharSequence;", "Ljava/lang/Math;", "Ljava/lang/StrictMath;", "Ljava/lang/System;",
            "Ljava/lang/Integer;", "Ljava/lang/Long;", "Ljava/lang/Character;", "Ljava/lang/Byte;",
            "Ljava/lang/Short;", "Ljava/lang/Boolean;", "Ljava/lang/Double;", "Ljava/lang/Float;", "Ljava/lang/Number;",
            "Ljava/lang/Class;", "Ljava/lang/Enum;",
            "Ljava/lang/Thread;", "Ljava/lang/StackTraceElement;", "Ljava/lang/Throwable;",
            "Ljava/lang/Exception;", "Ljava/lang/RuntimeException;",
            "Ljava/math/BigInteger;", "Ljava/math/BigDecimal;", "Ljava/math/MathContext;", "Ljava/math/RoundingMode;",
            "Ljava/io/InputStream;", "Ljava/io/OutputStream;", "Ljava/io/ByteArrayInputStream;", "Ljava/io/ByteArrayOutputStream;",
            "Ljava/io/DataInput;", "Ljava/io/DataOutput;", "Ljava/io/DataInputStream;", "Ljava/io/DataOutputStream;",
            "Ljava/io/FilterInputStream;", "Ljava/io/FilterOutputStream;", "Ljava/io/Closeable;", "Ljava/lang/AutoCloseable;",
            "Ljava/io/Reader;", "Ljava/io/StringReader;", "Ljava/io/InputStreamReader;", "Ljava/io/BufferedReader;",
            "Ljava/io/Writer;", "Ljava/io/StringWriter;", "Ljava/io/PrintWriter;",
            "Ljava/nio/IntBuffer;", "Ljava/nio/LongBuffer;", "Ljava/nio/ShortBuffer;",
            "Ljava/util/zip/CRC32C;", "Ljava/util/zip/InflaterInputStream;", "Ljava/util/zip/DeflaterInputStream;",
            "Ljava/util/zip/DeflaterOutputStream;", "Ljava/util/zip/InflaterOutputStream;",
            "Ljava/net/URI;", "Ljava/net/URLDecoder;", "Ljava/net/URLEncoder;",
            "Ljava/nio/ByteBuffer;", "Ljava/nio/CharBuffer;", "Ljava/nio/ByteOrder;",
            "Ljava/nio/charset/Charset;", "Ljava/nio/charset/StandardCharsets;",
            "Ljava/nio/charset/CharsetDecoder;", "Ljava/nio/charset/CharsetEncoder;",
            "Ljava/util/regex/Pattern;", "Ljava/util/regex/Matcher;", "Ljava/util/regex/MatchResult;",
            "Ljava/util/Arrays;", "Ljava/util/ArrayList;", "Ljava/util/LinkedList;", "Ljava/util/HashMap;",
            "Ljava/util/LinkedHashMap;", "Ljava/util/TreeMap;", "Ljava/util/HashSet;", "Ljava/util/LinkedHashSet;",
            "Ljava/util/TreeSet;", "Ljava/util/List;", "Ljava/util/Map;", "Ljava/util/Set;", "Ljava/util/Collection;",
            "Ljava/util/Collections;", "Ljava/util/Locale;", "Ljava/util/BitSet;", "Ljava/util/EnumMap;",
            "Ljava/util/EnumSet;", "Ljava/util/UUID;", "Ljava/util/StringTokenizer;",
            "Ljava/util/Objects;", "Ljava/util/Iterator;", "Ljava/lang/Iterable;", "Ljava/util/Map\$Entry;",
            "Ljava/util/AbstractList;", "Ljava/util/AbstractMap;", "Ljava/util/AbstractSet;", "Ljava/util/AbstractCollection;",
            "Ljava/util/Stack;", "Ljava/util/Vector;", "Ljava/util/ArrayDeque;", "Ljava/util/Deque;",
            "Ljava/util/Queue;", "Ljava/util/PriorityQueue;", "Ljava/util/Comparator;", "Ljava/lang/Comparable;",
            "Ljava/lang/AbstractStringBuilder;", "Ljava/text/DecimalFormat;", "Ljava/text/NumberFormat;",
            "Ljava/util/zip/CRC32;", "Ljava/util/zip/Adler32;", "Ljava/util/zip/Inflater;", "Ljava/util/zip/Deflater;",
            "Ljava/util/zip/GZIPInputStream;", "Ljava/util/zip/GZIPOutputStream;",
            "Ljava/security/MessageDigest;", "Ljavax/crypto/Cipher;", "Ljavax/crypto/Mac;",
            "Ljavax/crypto/SecretKeyFactory;", "Ljavax/crypto/SecretKey;", "Ljava/security/Key;",
            "Ljava/security/spec/KeySpec;", "Ljava/security/spec/AlgorithmParameterSpec;", "Ljava/security/KeyFactory;",
            "Ljavax/crypto/KeyGenerator;", "Ljavax/crypto/spec/SecretKeySpec;", "Ljavax/crypto/spec/IvParameterSpec;",
            "Ljavax/crypto/spec/GCMParameterSpec;", "Ljavax/crypto/spec/PBEKeySpec;", "Ljavax/crypto/spec/DESKeySpec;",
            "Ljava/util/Base64;", "Ljava/util/Base64\$Decoder;", "Ljava/util/Base64\$Encoder;",
            "Landroid/util/Base64;", "Landroid/util/Base64\$Decoder;", "Landroid/util/Base64\$Encoder;",
            "Landroid/os/Build;", "Landroid/text/TextUtils;", "Landroid/util/Log;", "Landroid/graphics/Color;",
        )

        /**
         * Instance methods whose result depends only on the receiver, so a call on a known receiver
         * can be folded to a constant.
         */
        val PURE_ACCESSORS = setOf(
            "Ljava/lang/Class;->getName", "Ljava/lang/Class;->getSimpleName",
            "Ljava/lang/Class;->getCanonicalName", "Ljava/lang/Class;->getTypeName",
            "Ljava/lang/String;->hashCode", "Ljava/lang/String;->length", "Ljava/lang/String;->isEmpty",
            "Ljava/lang/Integer;->hashCode", "Ljava/lang/Integer;->intValue",
            "Ljava/lang/Long;->hashCode", "Ljava/lang/Long;->longValue",
            "Ljava/lang/Short;->hashCode", "Ljava/lang/Short;->shortValue",
            "Ljava/lang/Byte;->hashCode", "Ljava/lang/Byte;->byteValue",
            "Ljava/lang/Character;->hashCode", "Ljava/lang/Character;->charValue",
            "Ljava/lang/Boolean;->hashCode", "Ljava/lang/Boolean;->booleanValue",
            "Ljava/lang/Double;->hashCode", "Ljava/lang/Double;->doubleValue",
            "Ljava/lang/Float;->hashCode", "Ljava/lang/Float;->floatValue",
            "Ljava/lang/Enum;->name", "Ljava/lang/Enum;->ordinal", "Ljava/lang/Enum;->hashCode",
        )

        /**
         * Methods that affect the host process or environment and are never executed.
         */
        val UNSAFE_METHODS = setOf(
            "Ljava/lang/System;->exit", "Ljava/lang/System;->getProperty", "Ljava/lang/System;->getenv",
            "Ljava/lang/System;->setProperty", "Ljava/lang/System;->clearProperty", "Ljava/lang/System;->load",
            "Ljava/lang/System;->loadLibrary", "Ljava/lang/System;->gc", "Ljava/lang/System;->console",
            "Ljava/lang/System;->setSecurityManager", "Ljava/lang/System;->runFinalization",
            "Ljava/lang/Math;->random",
            "Ljava/util/concurrent/TimeUnit;->sleep",
            "Ljava/lang/Thread;->interrupt", "Ljava/lang/Thread;->interrupted", "Ljava/lang/Thread;->isInterrupted",
            "Ljava/lang/Thread;->sleep", "Ljava/lang/Thread;->join", "Ljava/lang/Thread;->start",
            "Ljava/lang/Thread;->stop", "Ljava/lang/Thread;->suspend", "Ljava/lang/Thread;->resume",
            "Ljava/lang/Thread;->yield", "Ljava/lang/Thread;->onSpinWait", "Ljava/lang/Thread;->wait",
            "Ljava/lang/Thread;->setName", "Ljava/lang/Thread;->setPriority", "Ljava/lang/Thread;->setDaemon",
            "Ljava/lang/Thread;->setContextClassLoader", "Ljava/lang/Thread;->setUncaughtExceptionHandler",
        )

        /**
         * Methods whose result varies between runs; blocked so that analysis results are reproducible.
         */
        val NONDETERMINISTIC = setOf(
            "Ljava/lang/System;->currentTimeMillis", "Ljava/lang/System;->nanoTime",
            "Ljava/lang/System;->identityHashCode",
            "Ljava/lang/Object;->hashCode", "Ljava/lang/Object;->toString",
            "Ljava/util/UUID;->randomUUID",
        )
    }
}
