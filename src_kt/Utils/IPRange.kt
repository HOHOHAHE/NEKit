package Utils

import Utils.IPAddress
import Utils.IPInterval
import Utils.IPMask
import Utils.UInt128



// Define IPRange specific exceptions
open class IPRangeException(message: String) : Exception(message)
class InvalidCIDRFormatException(message: String = "Invalid CIDR format") : IPRangeException(message)
class InvalidRangeFormatException(message: String = "Invalid range format (e.g., ip+interval)") : IPRangeException(message)
class InvalidRangeException(message: String = "Start IP cannot be greater than end IP") : IPRangeException(message)
class InvalidFormatException(message: String = "Invalid IP or range string format") : IPRangeException(message)
class AddressIncompatibleException(message: String = "Start and end IP families are incompatible") : IPRangeException(message)
class IntervalInvalidException(message: String = "Calculated end IP from interval is invalid or incompatible") : IPRangeException(message)
class InvalidMaskException(message: String = "Unable to apply mask to base IP") : IPRangeException(message)


class IPRange(
    val startIP: IPAddress,
    val endIP: IPAddress
) {
    val family: IPAddress.Family

    init {
        if (startIP.family != endIP.family) {
            throw AddressIncompatibleException("Start IP family ${startIP.family} does not match end IP family ${endIP.family}")
        }
        if (startIP > endIP) { // Assumes IPAddress is Comparable
            throw InvalidRangeException("Start IP $startIP is greater than end IP $endIP")
        }
        this.family = startIP.family
    }

    // Secondary constructor for startIP and interval
    constructor(startIP: IPAddress, interval: IPInterval) : this(
        startIP,
        calculateEndIP(startIP, interval) // Helper function to calculate endIP and throw
    )

    // Secondary constructor for startIP and mask
    constructor(startIP: IPAddress, mask: IPMask) : this(
        calculateRangeFromMask(startIP, mask).first,
        calculateRangeFromMask(startIP, mask).second
    )

    fun contains(ip: IPAddress): Boolean {
        if (ip.family != family) {
            return false
        }
        // Assumes IPAddress is Comparable
        return ip >= startIP && ip <= endIP
    }

    companion object {
        private fun calculateEndIP(startIP: IPAddress, interval: IPInterval): IPAddress {
            if (startIP.family == IPAddress.Family.IPv4 && interval !is IPInterval.IPv4) {
                throw IntervalInvalidException("IPv4 address requires IPv4 interval")
            }
            if (startIP.family == IPAddress.Family.IPv6 && interval !is IPInterval.IPv6) {
                throw IntervalInvalidException("IPv6 address requires IPv6 interval")
            }
            return startIP.advanced(byInterval = interval) // Use the byInterval version
                ?: throw IntervalInvalidException("Failed to advance IP by interval (overflow or incompatibility)")
        }

        private fun calculateRangeFromMask(startIP: IPAddress, mask: IPMask): Pair<IPAddress, IPAddress> {
             if (startIP.family == IPAddress.Family.IPv4 && mask !is IPMask.IPv4) {
                throw InvalidMaskException("IPv4 address requires IPv4 mask")
            }
            if (startIP.family == IPAddress.Family.IPv6 && mask !is IPMask.IPv6) {
                throw InvalidMaskException("IPv6 address requires IPv6 mask")
            }
            return mask.getNetworkRange(startIP)
                ?: throw InvalidMaskException("Failed to calculate network range from mask")
        }

        // Factory method for CIDR string
        @Throws(IPRangeException::class)
        fun fromCIDRString(rep: String): IPRange {
            val parts = rep.split('/')
            if (parts.size != 2) throw InvalidCIDRFormatException("Expected format: IP/prefix")

            val ip = IPAddress.parse(parts[0]) ?: throw InvalidCIDRFormatException("Invalid IP address part: ${parts[0]}")

            val prefixLengthStr = parts[1]
            val mask = when (ip.family) {
                IPAddress.Family.IPv4 -> {
                    val prefix = prefixLengthStr.toUIntOrNull() ?: throw InvalidCIDRFormatException("Invalid IPv4 prefix length: $prefixLengthStr")
                    if (prefix > 32u) throw InvalidCIDRFormatException("IPv4 prefix length $prefix > 32")
                    IPMask.IPv4(prefix)
                }
                IPAddress.Family.IPv6 -> {
                    // Assuming prefix length for IPv6 is a simple integer string (0-128)
                    // The Swift code's use of UInt128.fromUnparsedString for prefix length was unusual.
                    val prefix = prefixLengthStr.toUIntOrNull() ?: throw InvalidCIDRFormatException("Invalid IPv6 prefix length: $prefixLengthStr")
                    if (prefix > 128u) throw InvalidCIDRFormatException("IPv6 prefix length $prefix > 128")
                    IPMask.IPv6(prefix) // Using UInt for IPMask.IPv6 prefixLength
                }
            }
            return IPRange(ip, mask)
        }

        // Factory method for "ip+interval" string
        @Throws(IPRangeException::class)
        fun fromRangeString(rep: String): IPRange {
            val parts = rep.split('+')
            if (parts.size != 2) throw InvalidRangeFormatException("Expected format: IP+intervalValue")

            val startIP = IPAddress.parse(parts[0]) ?: throw InvalidRangeFormatException("Invalid IP address part: ${parts[0]}")

            val intervalValueStr = parts[1]
            val interval = when (startIP.family) {
                IPAddress.Family.IPv4 -> {
                    val value = intervalValueStr.toUIntOrNull() ?: throw InvalidRangeFormatException("Invalid IPv4 interval value: $intervalValueStr")
                    IPInterval.IPv4(value)
                }
                IPAddress.Family.IPv6 -> {
                    // Assuming interval value for IPv6 is a simple integer string for count, not a full UInt128 string.
                    // If it's meant to be a full UInt128 string, UInt128.fromUnparsedString would be needed.
                    // For now, assuming it's a count that fits in ULong for simplicity with current UInt128 placeholder.
                    val value = intervalValueStr.toULongOrNull() ?: throw InvalidRangeFormatException("Invalid IPv6 interval value (expected ULong compatible): $intervalValueStr")
                    IPInterval.IPv6(UInt128(0uL, value)) // Constructing UInt128 from ULong
                }
            }
            return IPRange(startIP, interval)
        }

        // General factory method
        @Throws(IPRangeException::class)
        fun fromString(rep: String): IPRange {
            return when {
                rep.contains('/') -> fromCIDRString(rep)
                rep.contains('+') -> fromRangeString(rep)
                else -> {
                    val ip = IPAddress.parse(rep) ?: throw InvalidFormatException("Invalid IP address: $rep")
                    IPRange(ip, ip) // Range containing a single IP
                }
            }
        }
    }
}
