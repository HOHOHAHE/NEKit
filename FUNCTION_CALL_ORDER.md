# NEKit Key Functions and Call Order

This document outlines the typical call order of key functions in the NEKit library during its operation. This is based on the analysis of the codebase, particularly focusing on the demo application and core components.

## I. Initialization Phase

1.  **`AppDelegate.applicationDidFinishLaunching(_:)`** (in `NEKitDemo/AppDelegate.swift`)
    *   This is the entry point for the demo application.
    *   Initializes logging and observer factories.
    *   Creates a `Configuration` instance: `let config = Configuration()`
    *   Loads configuration from a file: `config.load(fromConfigFile: filepath)`
        *   **`Configuration.load(fromConfigFile:)`** (in `src/Config/Configuration.swift`)
            *   Reads the YAML configuration string from the file.
            *   Calls `Configuration.load(fromConfigString:)`.
        *   **`Configuration.load(fromConfigString:)`**
            *   Parses the YAML string: `Yaml.load(configString)`.
            *   Calls `AdapterFactoryParser.parseAdapterFactoryManager()` to set up adapter factories based on the "adapter" section of the config.
            *   Calls `RuleParser.parseRuleManager()` to set up rules based on the "rule" section of the config, linking them with the adapter factories. This initializes the `ruleManager` property of the `Configuration` object.
    *   Sets the static `RuleManager.currentManager`: `RuleManager.currentManager = config.ruleManager`.
    *   Initializes proxy servers:
        *   `httpProxy = GCDHTTPProxyServer(address: nil, port: ...)`
        *   `socks5Proxy = GCDSOCKS5ProxyServer(address: nil, port: ...)`
    *   Starts the proxy servers:
        *   `httpProxy!.start()`
        *   `socks5Proxy!.start()`
            *   **`GCDProxyServer.start()`** (superclass method, likely in `src/ProxyServer/GCDProxyServer.swift`)
                *   Sets up the listening socket.
                *   Starts accepting incoming connections.

## II. Connection Handling Phase (Example: HTTP Proxy)

This phase begins when a client sends a request to a NEKit proxy server (e.g., `GCDHTTPProxyServer`).

1.  **`GCDProxyServer.handleNewGCDSocket(_:)`** (polymorphic, e.g., in `src/ProxyServer/GCDHTTPProxyServer.swift`)
    *   This method is called when the server accepts a new client TCP connection (`GCDTCPSocket`).
    *   Creates a specific `ProxySocket` type to handle the protocol details of the incoming connection.
        *   For `GCDHTTPProxyServer`: `let proxySocket = HTTPProxySocket(socket: socket)`
        *   For `GCDSOCKS5ProxyServer`: `let proxySocket = SOCKS5ProxySocket(socket: socket)`
    *   Calls `GCDProxyServer.didAcceptNewSocket(proxySocket)`.

2.  **`GCDProxyServer.didAcceptNewSocket(_:)`** (in `src/ProxyServer/GCDProxyServer.swift`)
    *   This method (or a method it calls within the `ProxySocket` hierarchy) will eventually create a `ConnectSession` object.
    *   The `ConnectSession` encapsulates information about the client's request (e.g., target host, port).
    *   The `ProxySocket` (e.g., `HTTPProxySocket`) reads the initial request from the client to populate the `ConnectSession`.

3.  **`RuleManager.match(_:)`** (in `src/Rule/RuleManager.swift`)
    *   The `ProxySocket` (or a delegate) calls `RuleManager.currentManager.match(session)` with the created `ConnectSession`.
    *   This function iterates through the configured `Rule` objects.
    *   Each `Rule` object has a `match(_:)` method that returns an `AdapterFactory?`.
        *   **`Rule.match(_:)`** (specific to each rule type, e.g., `DirectRule.match(_:)`, `DomainListRule.match(_:)`)
            *   If the rule's criteria are met by the `ConnectSession`, it returns its configured `AdapterFactory`.
            *   Otherwise, it returns `nil`.
    *   The `RuleManager.match(_:)` returns the first non-nil `AdapterFactory` provided by a matching rule.

4.  **`AdapterFactory.getAdapterFor(session:)`** (polymorphic, e.g., in `src/Socket/AdapterSocket/Factory/HTTPAdapterFactory.swift` or the base class `src/Socket/AdapterSocket/Factory/AdapterFactory.swift`)
    *   The `ProxySocket` calls this method on the `AdapterFactory` returned by the `RuleManager`.
    *   This factory method instantiates and returns an appropriate `AdapterSocket` subclass (e.g., `DirectAdapter`, `HTTPAdapter`, `SOCKS5Adapter`).
        *   Example: `let adapter = DirectAdapter()`
        *   It also typically creates and assigns a `RawSocketProtocol` to the adapter's `socket` property: `adapter.socket = RawSocketFactory.getRawSocket()`.

5.  **`AdapterSocket.openSocketWith(session:)`** (in `src/Socket/AdapterSocket/AdapterSocket.swift`)
    *   The `ProxySocket` calls this method on the newly created `AdapterSocket`.
    *   This function initiates the connection to the actual destination server (or the next-hop proxy if the adapter is a proxy adapter).
    *   Sets `socket?.delegate = self` (`self` being the `AdapterSocket`).
    *   Calls `socket.connect()` (method of `RawTCPSocketProtocol`) to start the TCP connection.

## III. Data Transfer Phase

Once the `AdapterSocket` successfully connects to the remote server:

1.  **`AdapterSocket.didConnectWith(socket:)`** (delegate callback from `RawTCPSocketProtocol`)
    *   This method is called when the underlying `RawTCPSocketProtocol` establishes a connection.
    *   It updates the `AdapterSocket`'s status to `.established`.
    *   It then typically notifies its own delegate (which is usually the `ProxySocket`) that the connection is ready. E.g., `delegate?.didConnectWith(adapterSocket: self)`.
    *   The `ProxySocket` can now start relaying data.

2.  **Relaying Data from Client to Remote:**
    *   Client sends data to NEKit's proxy server.
    *   The `ProxySocket` reads this data from the client socket.
    *   The `ProxySocket` calls **`AdapterSocket.write(data:)`**.
        *   `AdapterSocket.write(data:)` calls `socket.write(data:)` on its `RawTCPSocketProtocol` to send data to the remote server.

3.  **`AdapterSocket.didWrite(data:by:)`** (delegate callback)
    *   Called when data has been successfully written to the `RawTCPSocketProtocol`.
    *   The `AdapterSocket` notifies its delegate (`ProxySocket`) that it's ready to send more data.

4.  **Relaying Data from Remote to Client:**
    *   The `RawTCPSocketProtocol` (managed by `AdapterSocket`) receives data from the remote server.
    *   **`AdapterSocket.didRead(data:from:)`** (delegate callback)
        *   This method is called when new data is available from the `RawTCPSocketProtocol`.
        *   The `AdapterSocket` passes this data to its delegate (`ProxySocket`). E.g., `delegate?.didRead(data:fromSocket:)`.
    *   The `ProxySocket` writes this data back to the client socket.

5.  **Continuous Data Flow:**
    *   To continue reading from the remote, the `ProxySocket` (after processing received data) would typically call **`AdapterSocket.readData()`** again.
        *   `AdapterSocket.readData()` calls `socket.readData()` on its `RawTCPSocketProtocol`.

## IV. Disconnection Phase

1.  **Initiation:** Disconnection can be initiated by the client, the remote server, or by NEKit itself (e.g., due to an error or rule change).

2.  **`AdapterSocket.disconnect(becauseOf:)`** or **`AdapterSocket.forceDisconnect(becauseOf:)`**
    *   These methods are called to close the connection handled by the `AdapterSocket`.
    *   They call `socket.disconnect()` or `socket.forceDisconnect()` on the `RawTCPSocketProtocol`.

3.  **`AdapterSocket.didDisconnectWith(socket:)`** (delegate callback)
    *   Called when the `RawTCPSocketProtocol` has disconnected.
    *   The `AdapterSocket` updates its status to `.closed`.
    *   Notifies its delegate (`ProxySocket`): `delegate?.didDisconnectWith(socket: self)`.
    *   The `ProxySocket` then closes the connection with the client.

4.  **`AppDelegate.applicationWillTerminate(_:)`**
    *   When the application is quitting, it calls `stop()` on the proxy servers.
        *   `httpProxy?.stop()`
        *   `socks5Proxy?.stop()`
            *   **`GCDProxyServer.stop()`**
                *   Stops accepting new connections.
                *   Closes the listening socket.
                *   May terminate active connections.

This call order provides a general guideline. Specific adapter implementations or rule behaviors might introduce variations or additional steps.
