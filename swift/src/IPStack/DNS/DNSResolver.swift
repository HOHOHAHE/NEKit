import Foundation

public protocol DNSResolverProtocol: AnyObject {
    var delegate: DNSResolverDelegate? { get set }
    func resolve(session: DNSSession)
    func stop()
}

public protocol DNSResolverDelegate: AnyObject {
    func didReceive(rawResponse: Data)
}

open class UDPDNSResolver: DNSResolverProtocol, NWUDPSocketDelegate {
    let socket: NWUDPSocket
    public weak var delegate: DNSResolverDelegate?

    public init(address: IPAddress, port: Port) {
        // Fallback to strict NWUDPSocket for DNS specifically if factory fails, though factory should handle it
        socket = (RawSocketFactory.getRawUDPSocket(host: address.presentation, port: Int(port.value), requestedInterface: .default) as? NWUDPSocket) ?? NWUDPSocket(host: address.presentation, port: Int(port.value))!
        socket.delegate = self
    }

    public func resolve(session: DNSSession) {
        socket.write(data: session.requestMessage.payload)
    }

    public func stop() {
        socket.disconnect()
    }

    public func didReceive(data: Data, from: NWUDPSocket) {
        delegate?.didReceive(rawResponse: data)
    }
    
    public func didCancel(socket: NWUDPSocket) {
        
    }
}
