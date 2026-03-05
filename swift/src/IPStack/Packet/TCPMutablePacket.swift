import Foundation

class TCPMutablePacket: IPMutablePacket {
    var sourcePort: Port {
        get {
            return Port(bytesInNetworkOrder: payload.bytes.advanced(by: IPHeaderLength))
        }
        set {
            setPort(oldPort: sourcePort, newPort: newValue, at: 0)
        }
    }

    var destinationPort: Port {
        get {
            return Port(bytesInNetworkOrder: payload.bytes.advanced(by: IPHeaderLength + 2))
        }
        set {
            setPort(oldPort: destinationPort, newPort: newValue, at: 2)
        }
    }

    override func updateChecksum(oldValue: UInt16, newValue: UInt16, type: ChangeType) {
        super.updateChecksum(oldValue: oldValue, newValue: newValue, type: type)
        updateChecksum(oldValue: oldValue, newValue: newValue, at: IPHeaderLength + 16)
    }

    private func setPort(oldPort: Port, newPort: Port, at: Int) {
        var netPort = newPort.valueInNetworkOrder
        payload.replaceBytes(in: NSRange(location: at + IPHeaderLength, length: 2), withBytes: &netPort, length: 2)
        updateChecksum(oldValue: oldPort.valueInNetworkOrder, newValue: newPort.valueInNetworkOrder, type: .port)
    }
}
