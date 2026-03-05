package nekit.IPStack.Packet

import nekit.IPStack.AddressFamily
import nekit.Utils.IPAddress
import nekit.IPStack.IPVersion
import nekit.IPStack.TransportProtocol
// import nekit.IPStack.Packet.IPDefs.TransportProtocol // Import from IPDefs (Removed - already imported from package)

interface IPPacket {
    val sourceAddress: IPAddress?
    val destinationAddress: IPAddress?
    val transportProtocol: TransportProtocol
    val protocolParser: UDPProtocolParser?
    val packetData: ByteArray?
    val version: IPVersion

    fun buildPacket()

    companion object {
        fun peekProtocol(packet: ByteArray): TransportProtocol {
            // Placeholder implementation
            return TransportProtocol.UNKNOWN // Referencing the one in IPDefs
        }
    }
}