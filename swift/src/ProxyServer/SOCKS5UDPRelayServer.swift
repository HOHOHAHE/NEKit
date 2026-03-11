import Foundation
import NetworkExtension
import CocoaLumberjackSwift
import CocoaAsyncSocket

/// Handles the UDP ASSOCIATE relay process for SOCKS5.
///
/// When a SOCKS5 client requests UDP ASSOCIATE, this server binds to an ephemeral UDP port
/// and relays datagrams between the client and the target server.
public class SOCKS5UDPRelayServer: NSObject, NWUDPSocketDelegate, GCDAsyncUdpSocketDelegate, NWCellularUDPSocketDelegate {
    
    // The expected client address and port from the TCP connection
    private let expectedClientAddress: IPAddress
    private let expectedClientPort: Port
    private weak var socks5ProxySocket: SOCKS5ProxySocket?
    public let outboundInterfaceType: NetworkInterfaceType
    
    // The socket listening for UDP datagrams from the local SOCKS5 client
    private var clientSocket: GCDAsyncUdpSocket?
    
    // A dictionary of outbound sockets mapped by target "host:port"
    private var outboundSockets: [String: AnyObject] = [:]
    public private(set) var boundAddress: IPAddress?
    public private(set) var boundPort: Port?
    
    private var actualClientAddress: IPAddress?
    private var actualClientPort: Port?
    
    private let queue = DispatchQueue(label: "com.zyxel.proxy.socks5udprelay")
    
    public init(expectedClientAddress: IPAddress, expectedClientPort: Port, socks5ProxySocket: SOCKS5ProxySocket, outboundInterfaceType: NetworkInterfaceType = .default) {
        self.expectedClientAddress = expectedClientAddress
        self.expectedClientPort = expectedClientPort
        self.socks5ProxySocket = socks5ProxySocket
        self.outboundInterfaceType = outboundInterfaceType
        super.init()
    }
    
    /// Starts the UDP relay server by binding to an ephemeral port.
    /// - Returns: true if successful, false otherwise.
    public func start() -> Bool {
        // Create the UDP socket for the client
        let socket = GCDAsyncUdpSocket(delegate: self, delegateQueue: DispatchQueue.global(qos: .userInitiated))
        
        do {
            // Bind to 127.0.0.1 on port 0 to get an ephemeral port
            try socket.bind(toPort: 0, interface: "127.0.0.1")
            try socket.beginReceiving()
        } catch {
            DDLogError("Failed to bind UDP socket for SOCKS5 Relay: \(error)")
            return false
        }
        
        self.clientSocket = socket
        
        // Fetch the local port that was assigned by the OS
        let boundPortValue = socket.localPort()
        
        self.boundAddress = IPAddress(fromString: "127.0.0.1")
        self.boundPort = Port(port: boundPortValue)
        
        DDLogInfo("SOCKS5 UDP Relay started for client requests on 127.0.0.1:\(boundPortValue).")
        return true
    }
    
    public func stop() {
        DDLogInfo("Stopping SOCKS5 UDP Relay.")
        clientSocket?.close()
        clientSocket = nil
        
        queue.sync {
            for (_, outSock) in outboundSockets {
                if let sock = outSock as? NWUDPSocket {
                    sock.disconnect()
                } else if let sock = outSock as? NWCellularUDPSocket {
                    sock.disconnect()
                }
            }
            outboundSockets.removeAll()
        }
    }
    
    // MARK: NWUDPSocketDelegate (for outbound)
    
    public func didReceive(data: Data, from: NWUDPSocket) {
        var expectedPortValue: UInt16 = 0
        var expectedIpStr = ""
        
        queue.sync {
            expectedPortValue = actualClientPort?.value ?? expectedClientPort.value
            expectedIpStr = actualClientAddress?.presentation ?? expectedClientAddress.presentation
        }

        let response = buildSOCKS5ResponseHeader(host: from.host, port: from.port, data: data)
        clientSocket?.send(response, toHost: expectedIpStr, port: expectedPortValue, withTimeout: -1, tag: 0)
    }
    
    public func didCancel(socket: NWUDPSocket) {
        // Outbound socket cancelled
    }
    
    // MARK: NWCellularUDPSocketDelegate (for outbound cellular)
    
    public func didReceive(data: Data, from: NWCellularUDPSocket) {
        var expectedPortValue: UInt16 = 0
        var expectedIpStr = ""
        
        queue.sync {
            expectedPortValue = actualClientPort?.value ?? expectedClientPort.value
            expectedIpStr = actualClientAddress?.presentation ?? expectedClientAddress.presentation
        }

        let response = buildSOCKS5ResponseHeader(host: from.host, port: from.port, data: data)
        clientSocket?.send(response, toHost: expectedIpStr, port: expectedPortValue, withTimeout: -1, tag: 0)
    }
    
    public func didCancel(socket: NWCellularUDPSocket) {
        // Outbound socket cancelled
    }
    
    // MARK: GCDAsyncUdpSocketDelegate (for incoming client)
    
    public func udpSocket(_ sock: GCDAsyncUdpSocket, didReceive data: Data, fromAddress address: Data, withFilterContext filterContext: Any?) {
        // Record the actual client address and port for the reply
        var host: NSString? = nil
        var port: UInt16 = 0
        GCDAsyncUdpSocket.getHost(&host, port: &port, fromAddress: address)
        
        queue.sync {
            self.actualClientAddress = IPAddress(fromString: (host as String?) ?? "")
            self.actualClientPort = Port(port: port)
        }
        
        handleDatagramFromClient(data: data)
    }
    
    public func udpSocketDidClose(_ sock: GCDAsyncUdpSocket, withError error: Error?) {
        DDLogInfo("SOCKS5 UDP Relay client socket closed.")
        stop()
    }
    
    private func handleDatagramFromClient(data: Data) {
        guard data.count >= 10 else {
            DDLogWarn("Received UDP datagram from client is too small: \(data.count) bytes")
            return
        }
        
        data.withUnsafeBytes { (bytes: UnsafePointer<UInt8>) in
            let rsv = (UInt16(bytes[0]) << 8) | UInt16(bytes[1])
            if rsv != 0 {
                DDLogWarn("Invalid RSV field in UDP datagram")
                return
            }
            
            let frag = bytes[2]
            if frag != 0 {
                return
            }
            
            let atyp = bytes[3]
            var offset = 4
            var destHostStr = ""
            
            switch atyp {
            case 0x01: // IPv4
                guard data.count >= offset + 4 + 2 else { return }
                destHostStr = "\(bytes[offset]).\(bytes[offset+1]).\(bytes[offset+2]).\(bytes[offset+3])"
                offset += 4
            case 0x03: // Domain
                guard data.count >= offset + 1 else { return }
                let domainLen = Int(bytes[offset])
                offset += 1
                guard data.count >= offset + domainLen + 2 else { return }
                let domainData = data.subdata(in: offset..<(offset+domainLen))
                destHostStr = String(data: domainData, encoding: .utf8) ?? ""
                offset += domainLen
            case 0x04: // IPv6
                guard data.count >= offset + 16 + 2 else { return }
                destHostStr = "IPv6_STUB" // Simplified
                offset += 16
            default:
                return
            }
            
            let destPortInt = Int((UInt16(bytes[offset]) << 8) | UInt16(bytes[offset+1]))
            offset += 2
            
            let payloadData = data.subdata(in: offset..<data.count)
            DDLogInfo("Relaying UDP: Client -> Target (\(destHostStr):\(destPortInt)) | \(payloadData.count) bytes payload")
            
            let outSocket = getOrCreateOutboundSocket(host: destHostStr, port: destPortInt)
            if let sock = outSocket as? NWUDPSocket {
                sock.write(data: payloadData)
            } else if let sock = outSocket as? NWCellularUDPSocket {
                sock.write(data: payloadData)
            }
        }
    }
    
    private func getOrCreateOutboundSocket(host: String, port: Int) -> AnyObject? {
        let key = "\(host):\(port)"
        
        var existingResult: AnyObject?
        queue.sync {
            existingResult = outboundSockets[key]
        }
        
        if let existing = existingResult {
            return existing
        }
        
        guard let socketObj = RawSocketFactory.getRawUDPSocket(host: host, port: port, requestedInterface: outboundInterfaceType) else {
            return nil
        }
        
        queue.sync {
            // Check again after lock to avoid duplicate sockets for the same target
            if let existing = outboundSockets[key] {
                existingResult = existing
            } else {
                outboundSockets[key] = socketObj
                existingResult = socketObj
            }
        }
        
        // If we picked up an existing one in the second check, return it.
        // Otherwise use the new one and set the delegate.
        if existingResult !== socketObj {
            return existingResult
        }
        
        if let socket = socketObj as? NWUDPSocket {
            socket.delegate = self
        } else if let socket = socketObj as? NWCellularUDPSocket {
            socket.delegate = self
        }
        return socketObj
    }
    
    private func buildSOCKS5ResponseHeader(host: String, port: Int, data: Data) -> Data {
        var response = Data([0x00, 0x00, 0x00]) // RSV + FRAG
        
        if let ip = IPAddress(fromString: host) {
            if ip.family == .IPv4 {
                response.append(0x01) // IPv4
                let ipBytes = host.components(separatedBy: ".").compactMap { UInt8($0) }
                if ipBytes.count == 4 {
                    response.append(contentsOf: ipBytes)
                } else {
                    response.append(contentsOf: [0, 0, 0, 0])
                }
            } else {
                response.append(0x04) // IPv6
                // Simplified, fallback if needed
                response.append(contentsOf: [UInt8](repeating: 0, count: 16))
            }
        } else {
            response.append(0x03) // Domain
            let hostData = host.data(using: .utf8) ?? Data()
            response.append(UInt8(hostData.count))
            response.append(hostData)
        }
        
        response.append(UInt8((port >> 8) & 0xFF))
        response.append(UInt8(port & 0xFF))
        response.append(data)
        
        return response
    }
}
