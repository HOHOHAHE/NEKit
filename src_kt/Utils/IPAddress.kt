package com.example.nekit.Utils

import java.net.InetAddress
import java.net.UnknownHostException
import org.slf4j.LoggerFactory // Assuming SLF4J is used for logging

/**
 * Represents an IP address (IPv4 or IPv6).
 * This class wraps `java.net.InetAddress` and provides utility methods.
 */
class IPAddress private constructor(private val inetAddress: InetAddress) : Comparable<IPAddress> {

    enum class Family {
        IPv4,
        IPv6
    }

    val family: Family
        get() = if (inetAddress is java.net.Inet4Address) Family.IPv4 else Family.IPv6

    val presentation: String
        get() = inetAddress.hostAddress

    val isIPv4: Boolean
        get() = family == Family.IPv4

    val isIPv6: Boolean
        get() = family == Family.IPv6

    val isIP: Boolean
        get() = true // Always true for an IPAddress object

    override fun compareTo(other: IPAddress): Int {
        // This is a simplified comparison. For full correctness, it should handle IPv4 vs IPv6
        // and byte-by-byte comparison. For now, comparing presentation strings.
        return this.presentation.compareTo(other.presentation)
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

    // Placeholder for advanced method, will be implemented later if needed
    fun advanced(by: UInt): IPAddress? {
        // This is a complex operation involving byte manipulation and handling overflows.
        // For now, return null as a placeholder.
        return null
    }

    // Placeholder for advanced method, will be implemented later if needed
    fun advanced(byInterval: IPInterval): IPAddress? {
        // This is a complex operation involving byte manipulation and handling overflows.
        // For now, return null as a placeholder.
        return null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IPAddress::class.java)

        /**
         * Parses an IP address string into an [IPAddress] object.
         * @param ipString The IP address string (e.g., "192.168.1.1" or "::1").
         * @return An [IPAddress] object if parsing is successful, null otherwise.
         */
        fun parse(ipString: String): IPAddress? {
            return try {
                IPAddress(InetAddress.getByName(ipString))
            } catch (e: UnknownHostException) {
                logger.debug("Failed to parse IPAddress from string '{}': {}", ipString, e.message)
                null
            } catch (e: Exception) {
                logger.warn("Unexpected error parsing IPAddress from string '{}': {}", ipString, e.message, e)
                null
            }
        }

        /**
         * Creates an [IPAddress] from a byte array.
         * @param bytes The byte array representing the IP address.
         * @param familyHint Optional hint for the address family (IPv4 or IPv6).
         * @return An [IPAddress] object if successful, null otherwise.
         */
        fun fromBytes(bytes: ByteArray, familyHint: Family? = null): IPAddress? {
            if (bytes.isEmpty()) {
                logger.debug("Invalid byte array length for IPAddress.fromBytes: ${bytes.size}. Family hint: $familyHint")
                return null
            }
            return try {
                IPAddress(InetAddress.getByAddress(bytes))
            } catch (e: UnknownHostException) {
                logger.warn("Failed to create IPAddress from bytes (length ${bytes.size}): {}", e.message)
                null
            }
        }

        /**
         * Creates an IPv4 [IPAddress] from a UInt32 in network byte order.
         * Placeholder - actual implementation would involve byte manipulation.
         */
        fun fromIPv4UInt32NetworkOrder(ipv4UInt32: UInt): IPAddress? {
            // This is a placeholder. Actual implementation would convert UInt to ByteArray and then to IPAddress.
            return null
        }

        /**
         * Creates an IPv6 [IPAddress] from a UInt128 in network byte order.
         * Placeholder - actual implementation would involve byte manipulation.
         */
        fun fromIPv6UInt128NetworkOrder(ipv6UInt128: UInt128): IPAddress? {
            // This is a placeholder. Actual implementation would convert UInt128 to ByteArray and then to IPAddress.
            return null
        }
    }
}