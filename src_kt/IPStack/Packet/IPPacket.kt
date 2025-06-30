package com.example.nekit.IPStack.Packet

import com.example.nekit.IPStack.AddressFamily
import com.example.nekit.Utils.IPAddress
import com.example.nekit.IPStack.IPVersion
import com.example.nekit.IPStack.TransportProtocol
// import com.example.nekit.IPStack.Packet.IPDefs.TransportProtocol // Import from IPDefs (Removed - already imported from package)

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