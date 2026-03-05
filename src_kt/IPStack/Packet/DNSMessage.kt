package nekit.IPStack.Packet

import nekit.IPStack.DNS.DNSQuery

interface DNSMessage {
    val queries: List<DNSQuery>
}
