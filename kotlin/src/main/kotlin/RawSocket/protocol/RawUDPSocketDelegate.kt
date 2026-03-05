package nekit.RawSocket.protocol

import java.lang.ref.WeakReference

interface RawUDPSocketDelegate {
    fun didReceive(data: ByteArray, from: RawUDPSocketProtocol)
    fun didCancel(socket: RawUDPSocketProtocol)
    fun didErrorOccur(error: Throwable, onSocket: RawUDPSocketProtocol) // Add this function
}