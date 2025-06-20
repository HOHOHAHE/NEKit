import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Error enum for UInt128 (simplified for now)
enum class UInt128Error {
    INVALID_STRING_CHARACTER,
    INVALID_RADIX,
    EMPTY_STRING,
    STRING_INPUT_OVERFLOW
}

data class UInt128(val upperBits: ULong, val lowerBits: ULong) : Comparable<UInt128> {

    constructor(value: Int) : this(0uL, value.toULong())
    constructor(value: Long) : this(0uL, value.toULong()) // Assumes positive, else conversion needed
    constructor(value: UInt) : this(0uL, value.toULong())
    constructor(value: ULong) : this(0uL, value)

    init {
        // Ensure values are treated as unsigned, though ULong handles this.
    }

    companion object {
        val MIN: UInt128 = UInt128(0uL, 0uL)
        val MAX: UInt128 = UInt128(ULong.MAX_VALUE, ULong.MAX_VALUE)
        const val SIZE_BITS: Int = 128
        const val SIZE_BYTES: Int = 16

        private val BIG_INT_TWO_POW_64 = BigInteger.ONE.shiftLeft(64)
        private val BIG_INT_MAX_UINT128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)


        fun fromBigInteger(bi: BigInteger): UInt128 {
            if (bi < BigInteger.ZERO || bi > BIG_INT_MAX_UINT128) {
                throw ArithmeticException("BigInteger value out of UInt128 range")
            }
            val bytes = bi.toByteArray()
            val paddedBytes = ByteArray(SIZE_BYTES)
            // Copy bytes, ensuring correct length and handling sign bit if present
            val start = if (bytes[0] == 0.toByte() && bytes.size > 1) 1 else 0
            val length = bytes.size - start
            val destPos = SIZE_BYTES - length
            System.arraycopy(bytes, start, paddedBytes, destPos, length)

            val bb = ByteBuffer.wrap(paddedBytes).order(ByteOrder.BIG_ENDIAN)
            return UInt128(bb.long.toULong(), bb.long.toULong())
        }

        @Throws(NumberFormatException::class)
        fun parseString(value: String, radix: Int = 10): UInt128 {
            if (value.isEmpty()) throw NumberFormatException("Empty string") // Or UInt128Error.EMPTY_STRING

            val actualRadix: Int
            var stringToParse = value.lowercase()

            when {
                stringToParse.startsWith("0x") -> {
                    actualRadix = 16
                    stringToParse = stringToParse.substring(2)
                }
                stringToParse.startsWith("0o") -> {
                    actualRadix = 8
                    stringToParse = stringToParse.substring(2)
                }
                stringToParse.startsWith("0b") -> {
                    actualRadix = 2
                    stringToParse = stringToParse.substring(2)
                }
                else -> actualRadix = radix
            }
            if (actualRadix != 2 && actualRadix != 8 && actualRadix != 10 && actualRadix != 16) {
                 throw NumberFormatException("Invalid radix: $actualRadix. Supported: 2, 8, 10, 16 (with 0b, 0o, 0x prefixes or explicit radix)")
            }


            val bi = BigInteger(stringToParse, actualRadix)
            return fromBigInteger(bi)
        }
    }

    fun toBigInteger(): BigInteger {
        // Convert ULongs to byte arrays (big-endian) and concatenate
        val bb = ByteBuffer.allocate(SIZE_BYTES)
        bb.putLong(upperBits.toLong())
        bb.putLong(lowerBits.toLong())
        return BigInteger(1, bb.array()) // 1 for positive signum
    }

    // --- Comparable ---
    override fun compareTo(other: UInt128): Int {
        if (upperBits < other.upperBits) return -1
        if (upperBits > other.upperBits) return 1
        if (lowerBits < other.lowerBits) return -1
        if (lowerBits > other.lowerBits) return 1
        return 0
    }

    // --- Arithmetic (simplified using BigInteger, direct implementation is very complex) ---
    operator fun plus(other: UInt128): UInt128 = fromBigInteger(this.toBigInteger().add(other.toBigInteger()))
    operator fun minus(other: UInt128): UInt128 = fromBigInteger(this.toBigInteger().subtract(other.toBigInteger()))
    operator fun times(other: UInt128): UInt128 = fromBigInteger(this.toBigInteger().multiply(other.toBigInteger()))
    operator fun div(other: UInt128): UInt128 {
        if (other == MIN) throw ArithmeticException("Division by zero")
        return fromBigInteger(this.toBigInteger().divide(other.toBigInteger()))
    }
    operator fun rem(other: UInt128): UInt128 {
        if (other == MIN) throw ArithmeticException("Division by zero for remainder")
        return fromBigInteger(this.toBigInteger().remainder(other.toBigInteger()))
    }

    // Overflowing arithmetic (from Swift's &+, &-, &*)
    fun addingReportingOverflow(other: UInt128): Pair<UInt128, Boolean> {
        val sum = this.toBigInteger().add(other.toBigInteger())
        val overflow = sum > BIG_INT_MAX_UINT128
        return Pair(fromBigInteger(sum.mod(BIG_INT_MAX_UINT128.add(BigInteger.ONE))), overflow)
    }
    fun subtractingReportingOverflow(other: UInt128): Pair<UInt128, Boolean> {
        val diff = this.toBigInteger().subtract(other.toBigInteger())
        val overflow = diff < BigInteger.ZERO
        return Pair(fromBigInteger(diff.mod(BIG_INT_MAX_UINT128.add(BigInteger.ONE))), overflow)
    }
    fun multipliedReportingOverflow(other: UInt128): Pair<UInt128, Boolean> {
        val prod = this.toBigInteger().multiply(other.toBigInteger())
        val overflow = prod > BIG_INT_MAX_UINT128
        return Pair(fromBigInteger(prod.mod(BIG_INT_MAX_UINT128.add(BigInteger.ONE))), overflow)
    }


    // --- Bitwise Operations (direct implementation for simple ones, BigInt for shifts) ---
    infix fun and(other: UInt128): UInt128 = UInt128(upperBits and other.upperBits, lowerBits and other.lowerBits)
    infix fun or(other: UInt128): UInt128 = UInt128(upperBits or other.upperBits, lowerBits or other.lowerBits)
    infix fun xor(other: UInt128): UInt128 = UInt128(upperBits xor other.upperBits, lowerBits xor other.lowerBits)
    fun inv(): UInt128 = UInt128(upperBits.inv(), lowerBits.inv())

    infix fun shl(n: Int): UInt128 {
        require(n >= 0) { "Shift count must be non-negative" }
        if (n == 0) return this
        if (n >= SIZE_BITS) return MIN
        return fromBigInteger(this.toBigInteger().shiftLeft(n))
    }

    infix fun shr(n: Int): UInt128 {
        require(n >= 0) { "Shift count must be non-negative" }
        if (n == 0) return this
        if (n >= SIZE_BITS) return MIN
        return fromBigInteger(this.toBigInteger().shiftRight(n))
    }

    // --- Byte Swapping ---
    // ULong.reverseBytes() is not standard in Kotlin common or JVM prior to some versions.
    // Implementing manually or using ByteBuffer.
    private fun ULong.reverseBytes(): ULong {
        return java.lang.Long.reverseBytes(this.toLong()).toULong()
    }

    val byteSwapped: UInt128 by lazy { // property for byteSwapped
        UInt128(this.lowerBits.reverseBytes(), this.upperBits.reverseBytes())
    }

    val bigEndian: UInt128
        get() = if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) this else byteSwapped

    val littleEndian: UInt128
        get() = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) this else byteSwapped


    // --- String representation ---
    fun toString(radix: Int): String {
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw IllegalArgumentException("Radix out of range: $radix")
        }
        return this.toBigInteger().toString(radix)
    }

    override fun toString(): String = toString(10)

    // --- Conversion to ByteArray ---
    fun toByteArray(): ByteArray {
        val bb = ByteBuffer.allocate(SIZE_BYTES)
        bb.order(ByteOrder.BIG_ENDIAN) // Store in Big Endian (Network Order)
        bb.putLong(upperBits.toLong())
        bb.putLong(lowerBits.toLong())
        return bb.array()
    }

    // Other methods from Swift (significantBits, toIntMax, etc.) would require more detailed implementation.
    // This provides a foundational UInt128.
}

// Extension for easy construction from Int if desired, like Swift
fun Int.toUInt128(): UInt128 = UInt128(this.toLong().toULong()) // Assumes positive for simplicity
fun UInt.toUInt128(): UInt128 = UInt128(this.toULong())
fun ULong.toUInt128(): UInt128 = UInt128(this)
