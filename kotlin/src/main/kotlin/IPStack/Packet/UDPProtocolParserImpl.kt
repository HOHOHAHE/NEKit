package nekit.IPStack.Packet

import nekit.Utils.Port

class UDPProtocolParserImpl : UDPProtocolParser {
    override var sourcePort: Port? = null
    override var destinationPort: Port? = null
    override var payloadData: ByteArray? = null

    constructor() // Default constructor

    // Constructor to parse UDP data (placeholder)
    constructor(data: ByteArray) {
        // Dummy parsing
        payloadData = data
    }
}
