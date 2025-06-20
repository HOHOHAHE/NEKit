import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays // For Arrays.equals on byte arrays if not using contentEquals

// --- Placeholders for types that need full translation later ---
// Placeholder for UInt128.kt (Simplified for IPAddress structure)
data class UInt128(val high: ULong, val low: ULong) : Comparable<UInt128> {
    constructor(value: UInt) : this(0uL, value.toULong())
    constructor(value: ULong) : this(0uL, value)

    // Dummy implementations for now
    val byteSwapped: UInt128 get() {
        // Highly simplified, real byte swapping for 128 bits is complex
        return UInt128(low.toULong().toString(16).padStart(16, '0').chunked(2).reversed().joinToString("").toULong(16),
                       high.toULong().toString(16).padStart(16, '0').chunked(2).reversed().joinToString("").toULong(16))

    }
    operator fun plus(other: UInt128): UInt128 = UInt128(high + other.high, low + other.low) // Incorrect, needs carry
    operator fun plus(other: UInt): UInt128 = this + UInt128(other)

    override fun compareTo(other: UInt128): Int {
        if (high < other.high) return -1
        if (high > other.high) return 1
        if (low < other.low) return -1
        if (low > other.low) return 1
        return 0
    }

    fun toByteArray(): ByteArray {
        val bb = ByteBuffer.allocate(16)
        bb.putLong(high.toLong())
        bb.putLong(low.toLong())
        return bb.array()
    }

    companion object {
        val ZERO = UInt128(0uL, 0uL)
        fun fromBytes(bytes: ByteArray): UInt128 {
            require(bytes.size == 16)
            val bb = ByteBuffer.wrap(bytes)
            return UInt128(bb.long.toULong(), bb.long.toULong())
        }
    }
}

// Placeholder for IPInterval.kt
sealed class IPInterval {
    data class IPv4(val value: UInt) : IPInterval() // Swift was UInt, Kotlin equivalent
    data class IPv6(val value: UInt128) : IPInterval()
}
// --- End Placeholders ---


// Using java.net.InetAddress as the backing object
class IPAddress private constructor(private val inetAddress: InetAddress) : Comparable<IPAddress> {

    enum class Family {
        IPv4, IPv6
    }

    val family: Family = when (inetAddress) {
        is Inet4Address -> Family.IPv4
        is Inet6Address -> Family.IPv6
        else -> throw IllegalArgumentException("Unknown InetAddress type")
    }

    val presentation: String by lazy {
        inetAddress.hostAddress
    }

    val isIPv4: Boolean get() = family == Family.IPv4
    val isIPv6: Boolean get() = family == Family.IPv6

    // Returns raw address bytes in network byte order (big-endian)
    val addressBytes: ByteArray get() = inetAddress.address

    // Comparable implementation
    override fun compareTo(other: IPAddress): Int {
        // Swift logic: IPv4 < IPv6. Otherwise compare bytes.
        if (this.isIPv4 && other.isIPv6) return -1
        if (this.isIPv6 && other.isIPv4) return 1

        // Same family, compare bytes
        val thisBytes = this.addressBytes
        val otherBytes = other.addressBytes

        // Ensure comparison is consistent for same length arrays (e.g. IPv4 vs IPv4, IPv6 vs IPv6)
        if (thisBytes.size < otherBytes.size) return -1 // Should not happen if families are same
        if (thisBytes.size > otherBytes.size) return 1 // Should not happen

        for (i in thisBytes.indices) {
            // Compare as unsigned bytes
            val tb = thisBytes[i].toInt() and 0xFF
            val ob = otherBytes[i].toInt() and 0xFF
            if (tb < ob) return -1
            if (tb > ob) return 1
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IPAddress) return false
        return inetAddress == other.inetAddress
    }

    override fun hashCode(): Int {
        return inetAddress.hashCode()
    }

    override fun toString(): String {
        return presentation
    }

    val uint32InNetworkOrder: UInt?
        get() = if (isIPv4) {
            ByteBuffer.wrap(addressBytes).order(ByteOrder.BIG_ENDIAN).int.toUInt()
        } else {
            null
        }

    val uint128InNetworkOrder: UInt128?
        get() = if (isIPv6) {
            val bytes = addressBytes
            require(bytes.size == 16)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            UInt128(bb.long.toULong(), bb.long.toULong())
        } else {
            null
        }


    fun advanced(by: UInt): IPAddress? {
        return when (family) {
            Family.IPv4 -> {
                val currentVal = this.uint32InNetworkOrder ?: return null
                // Swift code implies byteSwapped for arithmetic then byteSwapped back.
                // Let's do arithmetic in host order if currentVal is host order, or network order if it's network.
                // InetAddress.address gives network order. uint32InNetworkOrder is from network order bytes.
                // So, currentVal is effectively a big-endian integer.
                // BigInteger can handle overflow correctly.
                val bi = java.math.BigInteger(1, this.addressBytes) // 1 for positive signum
                val resultBytes = bi.add(java.math.BigInteger.valueOf(by.toLong())).toByteArray()
                // Ensure resultBytes is 4 bytes, BigInteger might return shorter/longer
                val finalBytes = ByteArray(4)
                if (resultBytes.size <= 4) {
                    System.arraycopy(resultBytes, 0, finalBytes, 4 - resultBytes.size, resultBytes.size)
                } else { // overflow beyond IPv4
                    return null
                }
                fromBytes(finalBytes, Family.IPv4)
            }
            Family.IPv6 -> {
                val currentVal = this.uint128InNetworkOrder ?: return null
                // Using BigInteger for 128-bit arithmetic
                val bi = java.math.BigInteger(1, this.addressBytes)
                val resultBytes = bi.add(java.math.BigInteger.valueOf(by.toLong())).toByteArray()
                val finalBytes = ByteArray(16)
                if (resultBytes.size <= 16) {
                     System.arraycopy(resultBytes, 0, finalBytes, 16 - resultBytes.size, resultBytes.size)
                } else { // overflow beyond IPv6
                    return null
                }
                fromBytes(finalBytes, Family.IPv6)
            }
        }
    }

    fun advanced(byInterval: IPInterval): IPAddress? {
        return when (family) {
            Family.IPv4 -> {
                if (byInterval !is IPInterval.IPv4) return null
                advanced(byInterval.value)
            }
            Family.IPv6 -> {
                 if (byInterval !is IPInterval.IPv6) return null
                // advanced for UInt128 + UInt128 needed
                val currentVal = java.math.BigInteger(1, this.addressBytes)
                val intervalBytes = byInterval.value.toByteArray() // Assuming UInt128.toByteArray() gives big-endian
                val intervalVal = java.math.BigInteger(1, intervalBytes)
                val resultBytes = currentVal.add(intervalVal).toByteArray()
                val finalBytes = ByteArray(16)
                if (resultBytes.size <= 16) {
                     System.arraycopy(resultBytes, 0, finalBytes, 16 - resultBytes.size, resultBytes.size)
                } else { return null } // overflow
                fromBytes(finalBytes, Family.IPv6)
            }
        }
    }


    companion object {
        funparse(ipString: String): IPAddress? {
            return try {
                IPAddress(InetAddress.getByName(ipString))
            } catch (e: UnknownHostException) {
                null
            }
        }

        fun fromBytes(bytes: ByteArray, family: Family? = null): IPAddress? {
            return try {
                // InetAddress.getByAddress can sometimes infer incorrectly if only bytes are given.
                // Explicitly check length for safety.
                when (family) {
                    Family.IPv4 -> if (bytes.size != 4) return null
                    Family.IPv6 -> if (bytes.size != 16) return null
                    null -> { // Try to infer if not specified
                        if (bytes.size != 4 && bytes.size != 16) return null
                    }
                }
                IPAddress(InetAddress.getByAddress(bytes))
            } catch (e: UnknownHostException) { // Should not happen if length is correct
                null
            }
        }

        fun fromIPv4UInt32NetworkOrder(ipv4UInt32: UInt): IPAddress? {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ipv4UInt32.toInt()).array()
            return fromBytes(bytes, Family.IPv4)
        }

        fun fromIPv6UInt128NetworkOrder(ipv6UInt128: UInt128): IPAddress? {
            // Assuming UInt128.toByteArray() returns big-endian (network order)
            val bytes = ipv6UInt128.toByteArray()
            if (bytes.size != 16) return null // Should be guaranteed by UInt128 impl
            return fromBytes(bytes, Family.IPv6)
        }
    }
}
