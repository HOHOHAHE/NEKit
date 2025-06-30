package com.example.nekit.RawSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.SocketProtocol

object RawSocketFactory {
    // Assuming LibTun2SocksStackInterface is a singleton or easily accessible
    // This is a placeholder for however you get the instance.


    fun getRawSocket(session: ConnectSession? = null): RawTCPSocketProtocol {
        // Logic to decide if a TUN socket should be created.
        // This is highly dependent on your application's logic.
        // For example, you might decide based on the session's destination address,
        // or a global configuration flag.
        // Here, we'll use a placeholder condition. Replace with your actual logic.


        // Fallback to a direct Ktor-based TCP socket if TUN is not required
        return KtorRawTCPClientSocket()
    }


}