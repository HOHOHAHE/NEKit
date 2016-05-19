import Foundation

enum IPVersion: Int {
    case IPv4 = 4, IPv6 = 6
}

enum IPProtocol: Int {
    case TCP = 6, UDP = 17
}

enum ChangeType {
    case Address, Port
}

public class IPPacket {
    // Support only IPv4 for now

    let version: IPVersion
    let proto: IPProtocol
    let IPHeaderLength: Int
    var sourceAddress: IPv4Address {
        get {
            return IPv4Address(fromBytesInNetworkOrder: payload.bytes.advancedBy(12))
        }
        set {
            setIPv4Address(sourceAddress, newAddress: newValue, at: 12)
        }
    }
    var destinationAddress: IPv4Address {
        get {
            return IPv4Address(fromBytesInNetworkOrder: payload.bytes.advancedBy(16))
        }
        set {
            setIPv4Address(destinationAddress, newAddress: newValue, at: 16)
        }
    }

    let payload: NSMutableData

    public init(payload: NSData) {
        let vl = UnsafePointer<UInt8>(payload.bytes).memory
        version = IPVersion(rawValue: Int(vl >> 4))!
        IPHeaderLength = Int(vl & 0x0F) * 4
        let p = UnsafePointer<UInt8>(payload.bytes.advancedBy(9)).memory
        proto = IPProtocol(rawValue: Int(p))!
        self.payload = NSMutableData(data: payload)
    }

    func updateChecksum(oldValue: UInt16, newValue: UInt16, type: ChangeType) {
        if type == .Address {
            updateChecksum(oldValue, newValue: newValue, at: 10)
        }
    }

    internal func updateChecksum(oldValue: UInt16, newValue: UInt16, at: Int) {
        let oldChecksum = UnsafePointer<UInt16>(payload.bytes.advancedBy(at)).memory
        let oc32 = UInt32(~oldChecksum)
        let ov32 = UInt32(~oldValue)
        let nv32 = UInt32(newValue)
        var newChecksum32 = oc32 &+ ov32 &+ nv32
        newChecksum32 = (newChecksum32 & 0xFFFF) + (newChecksum32 >> 16)
        newChecksum32 = (newChecksum32 & 0xFFFF) &+ (newChecksum32 >> 16)
        var newChecksum = ~UInt16(newChecksum32)
        payload.replaceBytesInRange(NSRange(location: at, length: 2), withBytes: &newChecksum, length: 2)
    }

    private func foldChecksum(var checksum: UInt32) -> UInt32 {
        while checksum > 0xFFFF {
            checksum = (checksum & 0xFFFF) + (checksum >> 16)
        }
        return checksum
    }

    private func setIPv4Address(oldAddress: IPv4Address, newAddress: IPv4Address, at: Int) {
        payload.replaceBytesInRange(NSRange(location: at, length: 4), withBytes: newAddress.bytesInNetworkOrder, length: 4)
            updateChecksum(UnsafePointer<UInt16>(oldAddress.bytesInNetworkOrder).memory, newValue: UnsafePointer<UInt16>(newAddress.bytesInNetworkOrder).memory, type: .Address)
            updateChecksum(UnsafePointer<UInt16>(oldAddress.bytesInNetworkOrder).advancedBy(1).memory, newValue: UnsafePointer<UInt16>(newAddress.bytesInNetworkOrder).advancedBy(1).memory, type: .Address)
        }

}
