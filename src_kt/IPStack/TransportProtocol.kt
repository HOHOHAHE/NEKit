package nekit.IPStack

enum class TransportProtocol(val value: Int) {
    UNKNOWN(0), // Assuming 0 as UNKNOWN value
    ICMP(1),
    TCP(6),
    UDP(17);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.value == value }
    }
}