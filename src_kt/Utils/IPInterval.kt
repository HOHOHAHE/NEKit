package Utils

// Assuming UInt128.kt will be available in the same package or imported.
// For now, it relies on the UInt128 placeholder defined previously (e.g., in IPAddress.kt or separately).

/**
 * Represents an interval for IP addresses, which can be different types for IPv4 and IPv6.
 */
sealed class IPInterval {
    /**
     * Represents an interval for an IPv4 address.
     * @property value The interval value as an unsigned 32-bit integer.
     */
    data class IPv4(val value: UInt) : IPInterval()

    /**
     * Represents an interval for an IPv6 address.
     * @property value The interval value as a UInt128 type.
     */
    data class IPv6(val value: UInt128) : IPInterval()
}
