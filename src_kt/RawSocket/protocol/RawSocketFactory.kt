package nekit.RawSocket.protocol

import nekit.Messages.ConnectSession
import nekit.Socket.SocketProtocol
import nekit.Config.NetworkInterfaceType
import nekit.Config.GlobalNetworkManager
import nekit.Utils.PlatformDetector
import nekit.RawSocket.cellular.RawCellularTCPSocket
import nekit.RawSocket.ktor.RawTCPSocket

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