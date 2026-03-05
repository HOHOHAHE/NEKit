package nekit.Utils

import java.math.BigInteger




/**
 * Represents an IP netmask defined by a prefix length.
 */
sealed class IPMask {
    /**
     * IPv4 mask defined by a prefix length.
     * @param prefixLength The number of leading bits in the network portion of the address (0-32).
     */
    data class IPv4(val prefixLength: UInt) : IPMask() {
        init {
            require(prefixLength <= 32u) { "IPv4 prefix length must be between 0 and 32." }
        }
    }

    /**
     * IPv6 mask defined by a prefix length.
     * @param prefixLength The number of leading bits in the network portion of the address (0-128).
     *                     The original Swift code used UInt128 for this, which is highly unusual.
     *                     Using UInt here for practical representation of a length up to 128.
     *                     If the original intent was different (e.g. actual mask bits in UInt128),
     *                     this would need significant re-evaluation.
     */
    data class IPv6(val prefixLength: UInt) : IPMask() { // Changed from UInt128 to UInt for sensibility
        init {
            require(prefixLength <= 128u) { "IPv6 prefix length must be between 0 and 128." }
        }
    }

    /**
     * Calculates the network base address and the last address (broadcast for IPv4, last address for IPv6 subnet).
     *
     * @param baseIP The IPAddress to apply the mask to.
     * @return A Pair where the first element is the network base IPAddress and the second is the last IPAddress
     *         in the range. Returns null if the mask and IP address families are mismatched or prefix is invalid.
     */
    fun getNetworkRange(baseIP: IPAddress): Pair<IPAddress, IPAddress>? {
        return when (this) {
            is IPv4 -> {
                if (!baseIP.isIPv4) return null
                val ipBytes = baseIP.addressBytes // Network order
                val ipInt = BigInteger(1, ipBytes) // Treat as positive number

                if (this.prefixLength == 0u) {
                    val base = IPAddress.fromIPv4UInt32NetworkOrder(0u) ?: return null
                    val end = IPAddress.fromIPv4UInt32NetworkOrder(UInt.MAX_VALUE) ?: return null
                    return Pair(base, end)
                }
                if (this.prefixLength == 32u) {
                    return Pair(baseIP, baseIP)
                }

                val hostBits = 32 - this.prefixLength.toInt()

                // Create mask by shifting 1s into the network part
                // e.g., prefix 24 -> 0xFFFFFF00
                val mask = BigInteger.valueOf(-1L).shiftLeft(hostBits).not() // Creates ...111000.. then NOT -> ...000111... (for host part)
                                                                          // then AND with IP. Or, create network mask directly.
                // Network mask: (0xFFFFFFFF << hostBits)
                val networkMask = BigInteger.valueOf(0xFFFFFFFFL).shiftLeft(hostBits) and BigInteger.valueOf(0xFFFFFFFFL)

                val baseAddressInt = ipInt and networkMask
                val lastAddressInt = baseAddressInt or networkMask.not() // OR with inverted network mask (host part is all 1s)

                val baseAddressBytes = baseAddressInt.toByteArray().ensureLength(4)
                val lastAddressBytes = lastAddressInt.toByteArray().ensureLength(4)

                val base = IPAddress.fromBytes(baseAddressBytes, IPAddress.Family.IPv4) ?: return null
                val end = IPAddress.fromBytes(lastAddressBytes, IPAddress.Family.IPv4) ?: return null
                Pair(base, end)
            }
            is IPv6 -> {
                if (!baseIP.isIPv6) return null
                val ipBytes = baseIP.addressBytes // Network order
                val ipInt = BigInteger(1, ipBytes)

                if (this.prefixLength == 0u) {
                    val base = IPAddress.fromIPv6UInt128NetworkOrder(UInt128.ZERO) ?: return null // Needs UInt128.ZERO
                    // Need UInt128.MAX for IPv6 broadcast equivalent
                    val maxIPv6Bytes = ByteArray(16) { 0xFF.toByte() }
                    val end = IPAddress.fromBytes(maxIPv6Bytes, IPAddress.Family.IPv6) ?: return null
                    return Pair(base, end)
                }
                if (this.prefixLength == 128u) {
                    return Pair(baseIP, baseIP)
                }

                val hostBits = 128 - this.prefixLength.toInt()

                // Create network mask: all 1s shifted left by hostBits
                // For BigInteger, a negative number with 'bitLength' equal to 128 can represent all 1s.
                // Or, (2^128 - 1) for all 1s.
                // ( (1 << networkBits) - 1 ) << hostBits
                val networkMask = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE) // All 1s for 128 bits
                                    .shiftRight(hostBits).shiftLeft(hostBits) // Clear host bits to 0 for network mask

                val baseAddressInt = ipInt and networkMask
                val lastAddressInt = baseAddressInt or networkMask.not() // OR with inverted network mask

                val baseAddressBytes = baseAddressInt.toByteArray().ensureLength(16)
                val lastAddressBytes = lastAddressInt.toByteArray().ensureLength(16)

                val base = IPAddress.fromBytes(baseAddressBytes, IPAddress.Family.IPv6) ?: return null
                val end = IPAddress.fromBytes(lastAddressBytes, IPAddress.Family.IPv6) ?: return null
                Pair(base, end)
            }
        }
    }
}

// Helper to ensure byte array is of specific length, padding with leading zeros if shorter, taking last bytes if longer.
private fun ByteArray.ensureLength(length: Int): ByteArray {
    if (this.size == length) return this
    val newArray = ByteArray(length)
    if (this.size > length) { // Too long, take the last 'length' bytes (most significant might be lost)
        System.arraycopy(this, this.size - length, newArray, 0, length)
    } else { // Too short, pad with leading zeros
        System.arraycopy(this, 0, newArray, length - this.size, this.size)
    }
    return newArray
}

// Need to add UInt128.ZERO and potentially UInt128.MAX if not using BigInteger for everything.
// For now, assuming UInt128.kt (placeholder) has ZERO. MAX can be constructed.
// The current UInt128 placeholder has a ZERO.
// For max IPv6, we can construct it with all FF bytes.
private val UInt128.Companion.MAX: UInt128 by lazy {
    UInt128(ULong.MAX_VALUE, ULong.MAX_VALUE)
}
