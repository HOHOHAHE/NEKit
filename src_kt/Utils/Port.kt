package nekit.Utils

/**
 * Represents a network port.
 *
 * @property hostOrderValue The port number in host byte order.
 */
data class Port(val hostOrderValue: Int) : Comparable<Port> {

    init {
        require(hostOrderValue in 0..65535) { "Port number must be between 0 and 65535." }
    }

    /**
     * Returns the port number in network byte order.
     */
    val networkOrderValue: Int
        get() = java.lang.Integer.reverseBytes(hostOrderValue)

    override fun compareTo(other: Port): Int {
        return this.hostOrderValue.compareTo(other.hostOrderValue)
    }

    override fun toString(): String {
        return hostOrderValue.toString()
    }
}