import Foundation
import NEKit

let directAdapterFactory = DirectAdapterFactory()
let allRule = AllRule(adapterFactory: directAdapterFactory)

let manager = RuleManager(fromRules: [allRule], appendDirect: true)
RuleManager.currentManager = manager

let server = GCDSOCKS5ProxyServer(address: IPAddress(fromString: "127.0.0.1"), port: Port(port: 9090))

do {
    try server.start()
    print("Local SOCKS5 Proxy started on 127.0.0.1:9090")
    print("You can test it with `curl -x http://127.0.0.1:9090 http://example.com`")
    
    // Keep the main thread alive to listen for connections
    RunLoop.main.run()
} catch {
    print("Failed to start server: \(error)")
}
