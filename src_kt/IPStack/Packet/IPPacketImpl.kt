package com.example.nekit.IPStack.Packet

import com.example.nekit.IPStack.AddressFamily
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

// Concrete implementation of IPPacket
class IPPacketImpl : IPPacket {
    override var sourceAddress: IPAddress? = null
    override var destinationAddress: IPAddress? = null
    override var transportProtocol: com.example.nekit.IPStack.TransportProtocol = com.example.nekit.IPStack.TransportProtocol.UNKNOWN // Referencing the one in IPDefs
    override var protocolParser: UDPProtocolParser? = null
    override var packetData: ByteArray? = null
    override var version: com.example.nekit.IPStack.IPVersion = com.example.nekit.IPStack.IPVersion.IPV4 // Default to IPv4

    override fun buildPacket() {
        // Placeholder implementation for building the packet
        packetData = ByteArray(0) // Dummy data
    }

    // Constructor to parse packet data (placeholder)
    constructor(packetData: ByteArray) {
        // Dummy parsing
        this.packetData = packetData
        this.sourceAddress = IPAddress.parse("0.0.0.0")
        this.destinationAddress = IPAddress.parse("0.0.0.0")
        this.transportProtocol = com.example.nekit.IPStack.TransportProtocol.UNKNOWN // Referencing the one in IPDefs
        this.version = com.example.nekit.IPStack.IPVersion.IPV4
    }

    constructor() : this(ByteArray(0))

    companion object {
        fun peekProtocol(packet: ByteArray): com.example.nekit.IPStack.TransportProtocol {
            // Placeholder implementation
            return com.example.nekit.IPStack.TransportProtocol.UNKNOWN // Referencing the one in IPDefs
        }
    }
}
