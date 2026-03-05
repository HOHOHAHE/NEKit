package com.example.nekit.RawSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.SocketProtocol
import com.example.nekit.Config.NetworkInterfaceType
import com.example.nekit.Config.GlobalNetworkManager
import com.example.nekit.Utils.PlatformDetector

object RawSocketFactory {

    fun getRawSocket(session: ConnectSession? = null): RawTCPSocketProtocol {
        val requestedInterface = session?.interfaceType ?: GlobalNetworkManager.currentActiveInterface

        // On Android: use RawCellularTCPSocket (supports network.bindSocket for cellular)
        // On macOS runLocal: fall back to KtorRawTCPClientSocket (no cellular concept on JVM)
        return if (PlatformDetector.isAndroid && requestedInterface == NetworkInterfaceType.CELLULAR) {
            RawCellularTCPSocket()
        } else {
            KtorRawTCPClientSocket()
        }
    }
}