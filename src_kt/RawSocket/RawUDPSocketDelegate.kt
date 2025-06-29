package com.example.nekit.RawSocket

/**
 * Delegate interface for handling events from a [RawUDPSocketProtocol] instance.
 */
interface RawUDPSocketDelegate {
    /**
     * Called when UDP data is received on the socket.
     *
     * @param data The ByteArray containing the data received.
     * @param fromHost The IP address string of the sender.
     * @param fromPort The port number of the sender.
     * @param onSocket The [RawUDPSocketProtocol] instance that received the data.
     */
    fun didReceive(data: ByteArray, fromHost: String, fromPort: Int, onSocket: RawUDPSocketProtocol)

    /**
     * Called when an error occurs on the socket (e.g., binding failure, send error, receive error).
     * Depending on the severity, the socket might become unusable after an error.
     *
     * @param error The error that occurred.
     * @param onSocket The [RawUDPSocketProtocol] instance where the error occurred.
     */
    fun didErrorOccur(error: Throwable, onSocket: RawUDPSocketProtocol)
}
