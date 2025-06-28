package Utils

import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays // For Arrays.equals on byte arrays if not using contentEquals




// Using java.net.InetAddress as the backing object
class IPAddress private constructor(private val inetAddress: InetAddress) : Comparable<IPAddress> {

    enum class Family {
        IPv4, IPv6
    }

    val family: Family = when (inetAddress) {
        is Inet4Address -> Family.IPv4
        is Inet6Address -> Family.IPv6
        else -> {
            logger.error("Unknown InetAddress type: ${inetAddress::class.java.name}. Defaulting to IPv4 if possible, but this is unexpected.")
            // This path should ideally not be taken if InetAddress.getByName or getByAddress work as expected.
            // Fallback or throw more specific error.
            throw IllegalArgumentException("Unknown InetAddress type: ${inetAddress::class.java.name}")
        }
    }

    val presentation: String by lazy {
        inetAddress.hostAddress ?: run {
            logger.warn("inetAddress.hostAddress returned null for $inetAddress, using canonicalHostName as fallback.")
            inetAddress.canonicalHostName ?: "" // Fallback, though hostAddress should generally be non-null for valid InetAddress
        }
    }

    val isIPv4: Boolean get() = family == Family.IPv4
    val isIPv6: Boolean get() = family == Family.IPv6

    // Returns raw address bytes in network byte order (big-endian)
    val addressBytes: ByteArray get() = inetAddress.address

    override fun compareTo(other: IPAddress): Int {
        if (this.isIPv4 && other.isIPv6) return -1
        if (this.isIPv6 && other.isIPv4) return 1
        val thisBytes = this.addressBytes
        val otherBytes = other.addressBytes
        if (thisBytes.size < otherBytes.size) return -1
        if (thisBytes.size > otherBytes.size) return 1
        for (i in thisBytes.indices) {
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

import Utils.UInt128.Companion.ZERO // Import ZERO

    val uint32InNetworkOrder: UInt?
        get() {
            return if (isIPv4) {
                ByteBuffer.wrap(addressBytes).order(ByteOrder.BIG_ENDIAN).int.toUInt()
            } else {
                null
            }
        }

    val uint128InNetworkOrder: UInt128?
        get() {
            return if (isIPv6) {
                val bytes = addressBytes
                if (bytes.size != 16) { // Should not happen for Inet6Address
                    logger.warn("IPv6 addressBytes length is not 16: ${bytes.size}. Cannot convert to UInt128.")
                    return null
                }
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                UInt128(bb.long.toULong(), bb.long.toULong())
            } else {
                null
            }
        }

    fun advanced(by: UInt): IPAddress? {
        val result = when (family) {
            Family.IPv4 -> {
                val bi = java.math.BigInteger(1, this.addressBytes)
                val resultBytes = bi.add(java.math.BigInteger.valueOf(by.toLong())).toByteArray()
                val finalBytes = ByteArray(4)
                if (resultBytes.size <= 4) {
                    System.arraycopy(resultBytes, 0, finalBytes, 4 - resultBytes.size, resultBytes.size)
                    fromBytes(finalBytes, Family.IPv4)
                } else { null }
            }
            Family.IPv6 -> {
                val bi = java.math.BigInteger(1, this.addressBytes)
                val resultBytes = bi.add(java.math.BigInteger.valueOf(by.toLong())).toByteArray()
                val finalBytes = ByteArray(16)
                if (resultBytes.size <= 16) {
                     System.arraycopy(resultBytes, 0, finalBytes, 16 - resultBytes.size, resultBytes.size)
                     fromBytes(finalBytes, Family.IPv6)
                } else { null }
            }
        }
        if (result == null) {
            logger.debug("Advancing IP address $this by $by resulted in overflow or error.")
        }
        return result
    }

    fun advanced(byInterval: IPInterval): IPAddress? {
        val result = when (family) {
            Family.IPv4 -> {
                if (byInterval !is IPInterval.IPv4) { logger.warn("Type mismatch: Advancing IPv4 address with IPv6 interval."); return null }
                advanced(byInterval.value)
            }
            Family.IPv6 -> {
                 if (byInterval !is IPInterval.IPv6) { logger.warn("Type mismatch: Advancing IPv6 address with IPv4 interval."); return null }
                val currentVal = java.math.BigInteger(1, this.addressBytes)
                // TODO: This relies on UInt128.toByteArray placeholder. If it's incorrect, this will be too.
                val intervalBytes = byInterval.value.toByteArray()
                val intervalVal = java.math.BigInteger(1, intervalBytes)
                val resultBytes = currentVal.add(intervalVal).toByteArray()
                val finalBytes = ByteArray(16)
                if (resultBytes.size <= 16) {
                     System.arraycopy(resultBytes, 0, finalBytes, 16 - resultBytes.size, resultBytes.size)
                     fromBytes(finalBytes, Family.IPv6)
                } else { null } // overflow
            }
        }
        if (result == null && byInterval is IPInterval.IPv4 && byInterval.value > 0u || byInterval is IPInterval.IPv6 && byInterval.value > UInt128.ZERO ) { // Log only if actual advance was attempted
            logger.debug("Advancing IP address $this by interval $byInterval resulted in overflow or error.")
        }
        return result
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IPAddress::class.java)

        fun parse(ipString: String): IPAddress? {
            return try {
                IPAddress(InetAddress.getByName(ipString))
            } catch (e: UnknownHostException) {
                logger.debug("Failed to parse IPAddress from string '{}': {}", ipString, e.message)
                null
            } catch (e: Exception) { // Catch any other unexpected errors during InetAddress creation
                logger.warn("Unexpected error parsing IPAddress from string '{}': {}", ipString, e.message, e)
                null
            }
        }

        fun fromBytes(bytes: ByteArray, familyHint: Family? = null): IPAddress? {
            // Validate byte array length based on family hint or content
            val validLength = when (familyHint) {
                Family.IPv4 -> bytes.size == 4
                Family.IPv6 -> bytes.size == 16
                null -> bytes.size == 4 || bytes.size == 16
            }
            if (!validLength) {
                logger.debug("Invalid byte array length for IPAddress.fromBytes: ${bytes.size}. Family hint: $familyHint")
                return null
            }

            return try {
                // InetAddress.getByAddress might try to infer family if not strict, but length check helps.
                // If familyHint is provided, we could be more specific, but InetAddress.getByAddress doesn't directly take it.
                IPAddress(InetAddress.getByAddress(bytes))
            } catch (e: UnknownHostException) {
                // This typically means invalid IP address format in bytes (e.g. wrong length not caught above)
                logger.warn("Failed to create IPAddress from bytes (length ${bytes.size}): {}", e.message)
                null
            }
        }

        fun fromIPv4UInt32NetworkOrder(ipv4UInt32: UInt): IPAddress? {
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(ipv4UInt32.toInt()).array()
            return fromBytes(bytes, Family.IPv4)
        }

        fun fromIPv6UInt128NetworkOrder(ipv6UInt128: UInt128): IPAddress? {
            // TODO: This relies on UInt128.toByteArray placeholder.
            val bytes = ipv6UInt128.toByteArray()
            if (bytes.size != 16) {
                logger.warn("fromIPv6UInt128NetworkOrder: UInt128.toByteArray() returned incorrect length: ${bytes.size}")
                return null
            }
            return fromBytes(bytes, Family.IPv6)
        }
    }
}
