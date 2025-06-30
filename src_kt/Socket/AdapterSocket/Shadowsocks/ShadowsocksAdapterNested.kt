package com.example.nekit.Socket.AdapterSocket.Shadowsocks

interface ShadowsocksAdapterNested {
    interface ProtocolObfuscaterFactory
    class OriginProtocolObfuscaterFactoryPlaceholder : ProtocolObfuscaterFactory
    class HTTPProtocolObfuscaterFactory(method: String, hosts: List<String>, customHeader: String?) : ProtocolObfuscaterFactory
    class TLSProtocolObfuscaterFactory(hosts: List<String>) : ProtocolObfuscaterFactory

    interface StreamObfuscaterFactory
    class OriginStreamObfuscaterFactoryPlaceholder : StreamObfuscaterFactory
    class OTAStreamObfuscaterFactory : StreamObfuscaterFactory

    class CryptoStreamProcessorFactoryPlaceholder(password: String, algorithm: Any) // Placeholder for CryptoAlgorithm
}
