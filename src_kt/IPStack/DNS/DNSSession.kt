package com.example.nekit.IPStack.DNS

import com.example.nekit.IPStack.Packet.DNSMessage
import com.example.nekit.Rule.Rule
import com.example.nekit.Utils.IPAddress

interface DNSSession {
    val requestMessage: DNSMessage
    val realIP: IPAddress?
    val matchedRule: Rule?
    val builtRequestPayload: ByteArray? // Add this property
}
