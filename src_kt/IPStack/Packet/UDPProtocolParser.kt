package nekit.IPStack.Packet

import nekit.Utils.IPAddress
import nekit.Utils.Port

interface UDPProtocolParser {
    val sourcePort: Port?
    val destinationPort: Port?
    val payloadData: ByteArray?
}