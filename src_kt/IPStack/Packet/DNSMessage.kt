package com.example.nekit.IPStack.Packet

import com.example.nekit.IPStack.DNS.DNSQuery

interface DNSMessage {
    val queries: List<DNSQuery>
}
