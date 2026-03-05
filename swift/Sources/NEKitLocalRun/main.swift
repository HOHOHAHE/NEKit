import Foundation
import NEKit

let server = GCDHTTPProxyServer(address: IPAddress(fromString: "127.0.0.1"), port: Port(port: 9090))

do {
    try server.start()
    print("Local HTTP Proxy started on 127.0.0.1:9090")
    print("You can test it with `curl -x http://127.0.0.1:9090 http://example.com`")
    
    // Keep the main thread alive to listen for connections
    RunLoop.main.run()
} catch {
    print("Failed to start server: \(error)")
}
