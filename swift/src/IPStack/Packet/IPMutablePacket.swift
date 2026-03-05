import Foundation

enum ChangeType {
    case address, port
}

public class IPMutablePacket {
    let version: IPVersion
    let proto: TransportProtocol
    let IPHeaderLength: Int
    
    var sourceAddress: IPAddress {
        get {
            return IPAddress(fromBytesInNetworkOrder: payload.bytes.advanced(by: 12))
        }
        set {
            setIPv4Address(oldAddress: sourceAddress, newAddress: newValue, at: 12)
        }
    }
    
    var destinationAddress: IPAddress {
        get {
            return IPAddress(fromBytesInNetworkOrder: payload.bytes.advanced(by: 16))
        }
        set {
            setIPv4Address(oldAddress: destinationAddress, newAddress: newValue, at: 16)
        }
    }

    let payload: NSMutableData

    public init(payload: NSData) {
        let vl = payload.bytes.assumingMemoryBound(to: UInt8.self).pointee
        version = IPVersion(rawValue: vl >> 4)!
        IPHeaderLength = Int(vl & 0x0F) * 4
        let p = payload.bytes.advanced(by: 9).assumingMemoryBound(to: UInt8.self).pointee
        proto = TransportProtocol(rawValue: p)!
        self.payload = NSMutableData(data: payload as Data)
    }

    func updateChecksum(oldValue: UInt16, newValue: UInt16, type: ChangeType) {
        if type == .address {
            updateChecksum(oldValue: oldValue, newValue: newValue, at: 10)
        }
    }

    internal func updateChecksum(oldValue: UInt16, newValue: UInt16, at: Int) {
        let oldChecksum = payload.bytes.advanced(by: at).assumingMemoryBound(to: UInt16.self).pointee
        let oc32 = UInt32(~oldChecksum)
        let ov32 = UInt32(~oldValue)
        let nv32 = UInt32(newValue)
        var newChecksum32 = oc32 &+ ov32 &+ nv32
        newChecksum32 = (newChecksum32 & 0xFFFF) + (newChecksum32 >> 16)
        newChecksum32 = (newChecksum32 & 0xFFFF) &+ (newChecksum32 >> 16)
        var newChecksum = ~UInt16(newChecksum32)
        payload.replaceBytes(in: NSRange(location: at, length: 2), withBytes: &newChecksum, length: 2)
    }

    private func setIPv4Address(oldAddress: IPAddress, newAddress: IPAddress, at: Int) {
        // IPAddress's withBytesInNetworkOrder allows fetching the network order payload
        let oldVal = oldAddress.UInt32InNetworkOrder!
        var newVal = newAddress.UInt32InNetworkOrder!
        
        payload.replaceBytes(in: NSRange(location: at, length: 4), withBytes: &newVal, length: 4)
        
        let oldPart1 = UInt16(truncatingIfNeeded: oldVal)
        let oldPart2 = UInt16(truncatingIfNeeded: oldVal >> 16)
        let newPart1 = UInt16(truncatingIfNeeded: newVal)
        let newPart2 = UInt16(truncatingIfNeeded: newVal >> 16)
        updateChecksum(oldValue: oldPart1, newValue: newPart1, type: .address)
        updateChecksum(oldValue: oldPart2, newValue: newPart2, type: .address)
    }
}
