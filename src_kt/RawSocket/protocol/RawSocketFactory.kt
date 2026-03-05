package com.example.nekit.RawSocket.protocol

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.SocketProtocol
import com.example.nekit.Config.NetworkInterfaceType
import com.example.nekit.Config.GlobalNetworkManager
import com.example.nekit.Utils.PlatformDetector
import com.example.nekit.RawSocket.cellular.RawCellularTCPSocket
import com.example.nekit.RawSocket.ktor.RawTCPSocket

object RawSocketFactory {

    fun getRawSocket(session: ConnectSession? = null): RawTCPSocketProtocol {
        val requestedInterface = if (session != null && session.interfaceType != NetworkInterfaceType.DEFAULT) {
            session.interfaceType
        } else {
            GlobalNetworkManager.currentActiveInterface
        }

        // On Android: use RawCellularTCPSocket (supports network.bindSocket for cellular)
        // On macOS runLocal: fall back to RawTCPSocket (no cellular concept on JVM)
        return if (PlatformDetector.isAndroid && requestedInterface == NetworkInterfaceType.CELLULAR) {
            RawCellularTCPSocket()
        } else {
            RawTCPSocket()
        }
    }
}