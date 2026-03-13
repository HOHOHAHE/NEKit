import Foundation
import NetworkExtension

/// The SOCKS5 proxy server.
public final class GCDSOCKS5ProxyServer: GCDProxyServer {
    private var _outboundInterfaceType: NetworkInterfaceType?
    public var outboundInterfaceType: NetworkInterfaceType {
        get { return _outboundInterfaceType ?? GlobalNetworkManager.shared.activeInterface }
        set { _outboundInterfaceType = newValue }
    }
    
    /// The tunnel provider used to create NWUDPSession / NWTCPConnection that bypass the VPN TUN.
    /// Set this to `self` from your `NEPacketTunnelProvider` subclass.
    public weak var tunnelProvider: NETunnelProvider?

    /**
     Create an instance of SOCKS5 proxy server.

     - parameter address: The address of proxy server.
     - parameter port:    The port of proxy server.
     */
    override public init(address: IPAddress?, port: Port) {
        super.init(address: address, port: port)
    }
    
    override public func start() throws {
        if let tp = tunnelProvider {
            RawSocketFactory.TunnelProvider = tp
        }
        try super.start()
    }

    /**
     Handle the new accepted socket as a SOCKS5 proxy connection.

     - parameter socket: The accepted socket.
     */
    override func handleNewGCDSocket(_ socket: GCDTCPSocket) {
        let proxySocket = SOCKS5ProxySocket(socket: socket)
        proxySocket.outboundInterfaceType = self.outboundInterfaceType
        didAcceptNewSocket(proxySocket)
    }
}
