package com.example.nekit.IPStack.Packet

interface TransportProtocolParser {
    var ipPacketRawData: ByteArray
    var transportHeaderOffset: Int
    val segmentLength: Int
    var payloadData: ByteArray?
    fun buildSegment(pseudoHeaderChecksum: UInt)
    fun parse()
}