import Foundation
import CocoaLumberjackSwift

#if canImport(NetworkExtension)
import NetworkExtension
public struct NetworkInterface {
    public static var TunnelProvider: NEPacketTunnelProvider!
}
#endif

public class Router {
    var IPv4NATRoutes: [Port: (IPAddress, Port)] = [:]
    let interfaceIP: IPAddress
    let fakeSourceIP: IPAddress
    let proxyServerIP: IPAddress
    let proxyServerPort: Port
    //    let IPv6NATRoutes: [UInt16] = []

    public init(interfaceIP: String, fakeSourceIP: String, proxyServerIP: String, proxyServerPort: UInt16) {
        self.interfaceIP = IPAddress(fromString: interfaceIP)!
        self.fakeSourceIP = IPAddress(fromString: fakeSourceIP)!
        self.proxyServerIP = IPAddress(fromString: proxyServerIP)!
        self.proxyServerPort = Port(port: proxyServerPort)
    }

    public func rewritePacket(packet: IPMutablePacket) -> IPMutablePacket? {
        // Support only TCP as for now
        guard packet.proto == .tcp else {
            return nil
        }

        guard let packet = packet as? TCPMutablePacket else {
            return nil
        }

        if packet.sourceAddress == interfaceIP {
            if packet.sourcePort == proxyServerPort {
                guard let (address, port) = IPv4NATRoutes[packet.destinationPort] else {
                    DDLogError("Does not know how to handle packet: \(packet) because can't find entry in NAT table.")
                    return nil
                }
                packet.sourcePort = port
                packet.sourceAddress = address
                packet.destinationAddress = interfaceIP
                return packet
            } else {
                IPv4NATRoutes[packet.sourcePort] = (packet.destinationAddress, packet.destinationPort)
                packet.sourceAddress = fakeSourceIP
                packet.destinationAddress = proxyServerIP
                packet.destinationPort = proxyServerPort
                return packet
            }
        } else {
            DDLogError("Does not know how to handle packet.")
            return nil
        }
    }

    public func startProcessPacket() {
        readAndProcessPackets()
    }

    func readAndProcessPackets() {
        NetworkInterface.TunnelProvider.packetFlow.readPackets() { packets, _ in
            var outputPackets = [IPMutablePacket]()
            
            for data in packets {
                let packet = IPMutablePacket(payload: data as NSData)
                if packet.version == .iPv4 && packet.proto == .tcp {
                    let tcpPacket = TCPMutablePacket(payload: packet.payload)
                    outputPackets.append(tcpPacket)
                }
            }
            
            for packet in outputPackets {
                DDLogVerbose("Received packet of type: \(packet.proto) from \(packet.sourceAddress) to \(packet.destinationAddress)")
                if let rewrittenPacket = self.rewritePacket(packet: packet) {
                    // Use it
                } else {
                    DDLogVerbose("Failed to rewrite packet \(packet)")
                }
            }

            let outputData = outputPackets.map { packet in
                packet.payload
            }

            if outputData.count > 0 {
                DDLogVerbose("Write out \(outputData.count) packets.")
                let dataArray = outputData.map { $0 as Data }
                NetworkInterface.TunnelProvider.packetFlow.writePackets(dataArray, withProtocols: Array<NSNumber>(repeating: NSNumber(value: AF_INET), count: outputData.count))
            }
            self.readAndProcessPackets()
        }
    }
}
