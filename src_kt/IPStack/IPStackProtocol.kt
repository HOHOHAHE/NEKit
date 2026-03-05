package nekit.IPStack
/**
 * The protocol defines an IP stack.
 * An IP stack is responsible for processing IP packets and interacting with a network interface.
 */
interface IPStackProtocol {
    /**
     * Inputs an IP packet into the stack for processing.
     *
     * @param packet The IP packet as a ByteArray.
     * @param version The version of the IP packet (e.g., AF_INET, AF_INET6 constants or similar).
     *                This can be used as a hint if not derivable from the packet itself.
     * @return True if the stack accepts and processes this packet. If true, the packet
     *         should not be processed by other stacks. False otherwise.
     */
    fun input(packet: ByteArray, version: Int?): Boolean

    /**
     * Callback function for this stack to output IP packets.
     * This is typically set by the network interface (e.g., TUN interface) when the stack is registered.
     * The function takes a list of packets (as ByteArrays) and a corresponding list of version numbers.
     *
     * Note: The original Swift version declared this as implicitly unwrapped.
     * In Kotlin, it's nullable and must be set before use.
     * Implementations should ensure thread safety if this function can be called from multiple threads.
     */
    var outputFunc: ((packets: List<ByteArray>, versions: List<AddressFamily>) -> Unit)?

    /**
     * Starts the IP stack.
     * Perform any necessary setup or resource allocation.
     */
    fun start()

    /**
     * Stops the IP stack from running.
     * This is called when the interface this stack is registered to stops processing packets
     * and is about to be released. Implementations should clean up resources.
     *
     * Provides a default empty implementation.
     */
    fun stop() {
        // Default implementation does nothing.
    }
}

enum class AddressFamily(val value: Int) {
    AF_INET(2), // Commonly used value for IPv4
    AF_INET6(30); // Or 10 on some systems like macOS for PF_INET6

    companion object {
        fun fromInt(value: Int) = values().first { it.value == value }
    }
}