package nekit.IPStack.DNS

import nekit.IPStack.Packet.DNSMessage
import nekit.Rule.Rule
import nekit.Utils.IPAddress

interface DNSSession {
    val requestMessage: DNSMessage
    val realIP: IPAddress?
    val matchedRule: Rule?
    val builtRequestPayload: ByteArray? // Add this property
}
