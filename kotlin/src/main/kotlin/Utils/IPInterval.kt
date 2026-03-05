package nekit.Utils

sealed class IPInterval {
    data class IPv4(val value: UInt) : IPInterval()
    data class IPv6(val value: UInt128) : IPInterval()
}