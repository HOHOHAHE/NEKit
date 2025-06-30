package com.example.nekit.IPStack.Packet

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

interface UDPProtocolParser {
    val sourcePort: Port?
    val destinationPort: Port?
    val payloadData: ByteArray?
}