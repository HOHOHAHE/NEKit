package Utils

import java.nio.ByteBuffer
import java.nio.ByteOrder



// Helper extension functions for UShort byte swapping
private fun UShort.toBigEndian(): UShort {
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) this
    else java.lang.Short.reverseBytes(this.toShort()).toUShort()
}

private fun UShort.fromBigEndian(): UShort {
    return if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) this
    else java.lang.Short.reverseBytes(this.toShort()).toUShort()
}

/**
 * Represents the port number of an IP protocol.
 * Internally stores the port in network byte order (Big Endian).
 */
@JvmInline
value class Port(
    /**
     * The port number in network byte order (Big Endian).
     */
    val networkOrderValue: UShort
) : Comparable<Port> { // Adding Comparable for completeness, though not in original Swift spec directly


    /**
     * The port number in host byte order.
     */
    val hostOrderValue: UShort
        get() = networkOrderValue.fromBigEndian()

    override fun toString(): String {
        return "<Port ${hostOrderValue.toInt()}>" // Display host order value
    }

    /**
     * Returns the port value as a ByteArray in network byte order.
     */
    fun toNetworkOrderByteArray(): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(networkOrderValue.toShort()).array()
    }

    override fun compareTo(other: Port): Int {
        return this.networkOrderValue.compareTo(other.networkOrderValue)
    }

    companion object {
        /**
         * Creates a Port from a UInt, interpreting it as a host order port value.
         * Useful for literal-like creation: Port(80u)
         */
        operator fun invoke(hostOrderPort: UInt): Port {
            require(hostOrderPort <= UShort.MAX_VALUE.toUInt()) { "Port value exceeds UShort range."}
            return Port(hostOrderPort.toUShort())
        }

        /**
         * Creates a Port from a ByteArray containing bytes in network order.
         * The ByteArray must be exactly 2 bytes long.
         *
         * @param bytesInNetworkOrder ByteArray with 2 bytes representing the port in network order.
         * @throws IllegalArgumentException if the byte array is not 2 bytes long.
         */
        fun fromNetworkOrderBytes(bytesInNetworkOrder: ByteArray): Port {
            require(bytesInNetworkOrder.size == 2) { "Byte array must be 2 bytes long for Port." }
            val value = ByteBuffer.wrap(bytesInNetworkOrder).order(ByteOrder.BIG_ENDIAN).short.toUShort()
            return Port(value) // Port constructor expects networkOrderValue
        }
    }
}
