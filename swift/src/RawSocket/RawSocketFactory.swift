import Foundation
import NetworkExtension

/**
 Represents the type of the socket.

 - NW:  The socket based on `NWTCPConnection`.
 - GCD: The socket based on `GCDAsyncSocket`.
 */
public enum SocketBaseType {
    case nw, gcd
}

/// Factory to create `RawTCPSocket` based on configuration.
open class RawSocketFactory {
    /// Current active `NETunnelProvider` which creates `NWTCPConnection` instance.
    ///
    /// - note: Must set before any connection is created if `NWTCPSocket` or `NWUDPSocket` is used.
    public static weak var TunnelProvider: NETunnelProvider?

    /**
     Return `RawTCPSocketProtocol` instance.

     - parameter type: The type of the socket (`SocketBaseType`).
     - parameter requestedInterface: The required network interface (`NetworkInterfaceType`).

     - returns: The created socket instance.
     */
    public static func getRawTCPSocket(_ type: SocketBaseType? = nil, requestedInterface: NetworkInterfaceType? = nil) -> RawTCPSocketProtocol {
        let activeInterface = requestedInterface ?? GlobalNetworkManager.shared.activeInterface

        switch type {
        case .some(.nw):
            return activeInterface == .cellular ? NWCellularTCPSocket() : NWTCPSocket()
        case .some(.gcd):
            // GCD sockets don't naturally support cellular binding through NWParameters easily here.
            return GCDTCPSocket()
        case nil:
            if RawSocketFactory.TunnelProvider == nil {
                // No tunnel extension — use NWCellularTCPSocket when cellular is requested,
                // otherwise fall back to GCDTCPSocket (OS picks the default route).
                return activeInterface == .cellular ? NWCellularTCPSocket() : GCDTCPSocket()
            } else {
                return activeInterface == .cellular ? NWCellularTCPSocket() : NWTCPSocket()
            }
        }
    }

    /**
     Return `NWUDPSocket` instance.
     Note: In Swift, GCDUDPSocket may not be fully managed by this factory, so we focus on NWUDPSocket.

     - parameter host: The host to connect to.
     - parameter port: The port to connect to.
     - parameter timeout: The timeout for the socket.
     - parameter requestedInterface: The required network interface (`NetworkInterfaceType`).

     - returns: The created socket instance or nil if initialization failed.
     */
    public static func getRawUDPSocket(host: String, port: Int, timeout: Int = Opt.UDPSocketActiveTimeout, requestedInterface: NetworkInterfaceType? = nil) -> AnyObject? {
        let activeInterface = requestedInterface ?? GlobalNetworkManager.shared.activeInterface
        
        if activeInterface == .cellular {
            return NWCellularUDPSocket(host: host, port: port, timeout: timeout)
        } else {
            return NWUDPSocket(host: host, port: port, timeout: timeout)
        }
    }
}
