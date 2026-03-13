package nekit.RawSocket.protocol

import nekit.Messages.ConnectSession
import nekit.Socket.SocketProtocol
import nekit.Config.NetworkInterfaceType
import nekit.Config.GlobalNetworkManager
import nekit.Utils.PlatformDetector
import nekit.RawSocket.cellular.RawCellularTCPSocket
import nekit.RawSocket.ktor.RawTCPSocket
import nekit.RawSocket.cellular.RawCellularUDPSocket
import nekit.RawSocket.ktor.RawUDPSocket

object RawSocketFactory {

    fun getRawTCPSocket(session: ConnectSession? = null): RawTCPSocketProtocol {
        val requestedInterface = if (session != null && session.interfaceType != NetworkInterfaceType.DEFAULT) {
            session.interfaceType
        } else {
            GlobalNetworkManager.activeInterface
        }

        // On Android: use RawCellularTCPSocket (supports network.bindSocket for cellular)
        // On macOS runLocal: fall back to RawTCPSocket (no cellular concept on JVM)
        return if (PlatformDetector.isAndroid && requestedInterface == NetworkInterfaceType.CELLULAR) {
            RawCellularTCPSocket()
        } else {
            RawTCPSocket()
        }
    }

    fun getRawUDPSocket(host: String, port: Int, requestedInterface: NetworkInterfaceType? = null): RawUDPSocketProtocol {
        val activeInterface = if (requestedInterface != null && requestedInterface != NetworkInterfaceType.DEFAULT) {
            requestedInterface
        } else {
            GlobalNetworkManager.activeInterface
        }

        return if (PlatformDetector.isAndroid && activeInterface == NetworkInterfaceType.CELLULAR) {
            RawCellularUDPSocket(host, port)
        } else {
            RawUDPSocket(host, port)
        }
    }
}