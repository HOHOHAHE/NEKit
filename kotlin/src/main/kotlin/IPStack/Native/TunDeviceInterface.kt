package nekit.IPStack.Native // Assuming this package structure

/**
 * Kotlin interface defining operations for interacting with a TUN device.
 * This interface will be implemented using JNA to call native C functions.
 */
interface TunDeviceInterface {
    /**
     * Opens a TUN device.
     *
     * @param name The desired name for the TUN interface (e.g., "tun0", "utun").
     *             If null, the system might assign a default name.
     * @return A file descriptor (integer) or handle representing the TUN device if successful,
     *         or a negative value/error code if opening fails.
     */
    fun openTun(name: String?): Int

    /**
     * Closes a TUN device.
     *
     * @param fd The file descriptor or handle of the TUN device to close.
     * @return 0 on success, or a negative value/error code on failure.
     */
    fun closeTun(fd: Int): Int

    /**
     * Reads an IP packet from the TUN device.
     *
     * @param fd The file descriptor or handle of the TUN device.
     * @param buffer The byte array to store the read packet.
     * @param bufferOffset The offset in the buffer where writing should start.
     * @param length The maximum number of bytes to read into the buffer.
     * @return The number of bytes read if successful, 0 if no packet was available (non-blocking),
     *         or a negative value/error code if an error occurred.
     */
    fun readPacket(fd: Int, buffer: ByteArray, bufferOffset: Int, length: Int): Int

    /**
     * Writes an IP packet to the TUN device.
     *
     * @param fd The file descriptor or handle of the TUN device.
     * @param buffer The byte array containing the packet to write.
     * @param bufferOffset The offset in the buffer from where reading should start.
     * @param length The number of bytes to write from the buffer.
     * @return The number of bytes written if successful, or a negative value/error code if an error occurred.
     */
    fun writePacket(fd: Int, buffer: ByteArray, bufferOffset: Int, length: Int): Int

    /**
     * Retrieves the actual name of the TUN interface associated with the file descriptor.
     * This is useful if the system assigns a name dynamically (e.g., "utun" on macOS).
     *
     * @param fd The file descriptor or handle of the TUN device.
     * @return The interface name as a String, or null if it cannot be retrieved or an error occurs.
     */
    fun getTunName(fd: Int): String?

    /**
     * Retrieves the Maximum Transmission Unit (MTU) for a given network interface name.
     *
     * @param interfaceName The name of the network interface (e.g., "tun0", "en0").
     * @return The MTU value as an Int, or a default/error value (e.g., -1 or 1500) if it cannot be determined.
     */
    fun getMTU(interfaceName: String): Int

    // Optional methods for network interface configuration (if not handled by external scripts):
    // fun setInterfaceAddress(interfaceName: String, ipAddress: String, netmask: String): Int
    // fun setInterfaceUp(interfaceName: String): Int
    // fun setInterfaceDown(interfaceName: String): Int
    // fun addRoute(destination: String, netmask: String, gateway: String, interfaceName: String?): Int
    // fun deleteRoute(destination: String, netmask: String, gateway: String, interfaceName: String?): Int
}
