# NEKit Swift to Kotlin Translation Plan

## 1. Introduction

### 1.1. Purpose of this Document
This document outlines a comprehensive plan for translating the core functionalities of the NEKit Swift-based networking proxy library into Kotlin. It details the analysis of the existing Swift components, proposes a Kotlin project structure, maps Swift concepts to Kotlin equivalents, discusses key component designs, identifies necessary dependencies, suggests a phased translation approach, and highlights potential challenges.

### 1.2. Background: NEKit (Swift) Overview
NEKit is a powerful and flexible networking toolkit written in Swift. Its primary capabilities include acting as an HTTP and SOCKS5 proxy server, enabling rule-based routing of network traffic through various adapter types (e.g., direct connection, upstream proxies). It is known for its robust implementation of network protocols and its utility in creating custom proxy solutions on Apple platforms.

### 1.3. Goals of the Kotlin Translation
The primary goals for translating NEKit to Kotlin are:
*   **Platform Expansion:** Enable NEKit's core proxying capabilities to run on the Java Virtual Machine (JVM), opening it up to server-side applications, Android development (with further adaptation), and other JVM-based environments.
*   **Leveraging Kotlin Features:** Utilize Kotlin's modern language features, such as coroutines for asynchronous programming, extension functions, data classes, and sealed classes, to create a more idiomatic, maintainable, and potentially more performant library on the JVM.
*   **Community and Ecosystem:** Tap into the extensive Kotlin and Java library ecosystem for networking, cryptography, and other utilities.

### 1.4. Scope of Translation
This translation plan focuses on porting the core proxy functionality of NEKit. Specifically, it will cover:
*   HTTP Proxying (server-side handling of HTTP requests).
*   SOCKS5 Proxying (server-side handling of SOCKS5 requests).
*   Direct connection adapter (connecting directly to the destination).
*   HTTP connection adapter (connecting through an upstream HTTP proxy).
*   SOCKS5 connection adapter (connecting through an upstream SOCKS5 proxy).
*   Rule-based routing engine (`RuleManager` and basic rule types).
*   Core networking abstractions for sockets and connection sessions.
Advanced features like Shadowsocks (unless explicitly added later), complex UI components, or platform-specific Network Extension features from the original Swift version are initially out of scope.

### 1.5. Target Platform for Kotlin Version
The initial target platform for the Kotlin version will be the **Java Virtual Machine (JVM)** (Java 8 or higher). This allows for broad applicability on servers and desktops. While Kotlin Multiplatform (KMP) is a potential future direction for wider reach (e.g., Android, native desktop), this plan will first focus on a solid JVM implementation to validate the core logic translation. KMP considerations can be addressed in a later phase.

## 2. Analysis of NEKit (Swift) Core Components

### 2.1. Identified Core Swift Files/Modules for Translation
Based on previous analysis, the following Swift files/modules represent the core functionality targeted for translation:
*   `ProxyServer.swift` (and `GCDProxyServer.swift` as its concrete implementation base)
*   `ProxySocket.swift` (base for client-side connection handling)
*   `HTTPProxySocket.swift`
*   `SOCKS5ProxySocket.swift`
*   `Tunnel.swift` (orchestrates ProxySocket and AdapterSocket)
*   `RuleManager.swift` (handles routing logic)
*   `Rule.swift` (and its various concrete rule implementations like `DirectRule.swift`, `DomainListRule.swift`, etc.)
*   `AdapterSocket.swift` (base for outgoing connections)
*   `DirectAdapter.swift`
*   `HTTPAdapter.swift`
*   `SOCKS5Adapter.swift`
*   `AdapterFactory.swift` (and specific factory implementations)
*   `ConnectSession.swift` (data object for connection details)
*   `RawTCPSocketProtocol.swift` (abstraction for TCP operations)
*   `GCDTCPSocket.swift` (concrete TCP socket implementation)
*   Utility files related to `IPAddress`, `Port`, `HTTPHeader`, etc.

### 2.2. Key Responsibilities of Core Swift Components
*   **`ProxyServer` / `GCDProxyServer`**: Listens for incoming client connections on specified ports (e.g., HTTP on 8080, SOCKS5 on 1080). Manages active client connections (`Tunnel`s).
*   **`ProxySocket` / `HTTPProxySocket` / `SOCKS5ProxySocket`**: Handles the client-facing side of a connection. Parses the incoming protocol (HTTP, SOCKS5 handshake), extracts destination information (`ConnectSession`), and communicates this to the `Tunnel`.
*   **`Tunnel`**: Orchestrates the entire lifecycle of a single proxied connection. It links a `ProxySocket` (client-side) with an appropriate `AdapterSocket` (server-side), facilitating data transfer between them once both are ready.
*   **`RuleManager`**: Contains a list of `Rule`s. It matches an incoming `ConnectSession` against these rules to determine which `AdapterFactory` (and thus, which `AdapterSocket`) should be used to handle the outgoing connection.
*   **`AdapterSocket` / `DirectAdapter` / `HTTPAdapter` / `SOCKS5Adapter`**: Handles the server-facing side of a connection. Establishes the outgoing connection to the actual destination or an upstream proxy, performing any necessary protocol handshakes (e.g., HTTP CONNECT, SOCKS5 negotiation).
*   **`AdapterFactory`**: Responsible for creating instances of specific `AdapterSocket` types based on routing decisions.
*   **`ConnectSession`**: A data class holding all relevant information about a connection request (host, port, resolved IP, etc.).
*   **`RawTCPSocketProtocol` / `GCDTCPSocket`**: Provides an abstraction over low-level TCP socket operations (connect, read, write, close), with `GCDTCPSocket` leveraging Grand Central Dispatch for asynchronous operations in the Swift version.

## 3. Proposed Kotlin Project Structure

### 3.1. Directory Layout
A standard Gradle-based project structure will be used:
```
nekit-kotlin/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/          # Kotlin source files
    │   └── resources/       # Application resources
    └── test/
        ├── kotlin/          # Kotlin test files
        └── resources/       # Test resources
```

### 3.2. Base Package Name
The proposed base package name will be `io.github.hohohahe.nekitkotlin`.

### 3.3. Detailed Package Structure
```
io.github.hohohahe.nekitkotlin/
├── core/                  # Core data classes and utilities
│   ├── ConnectSession.kt
│   ├── IpAddress.kt
│   ├── Port.kt
│   └── utils/             # Common helper functions
├── config/                # Configuration loading and parsing
│   ├── Configuration.kt
│   └── RuleConfig.kt
├── crypto/                # Cryptographic utilities (e.g., for Shadowsocks if added later)
├── proxyserver/           # Proxy server implementations
│   ├── ProxyServer.kt     # Interface or abstract class
│   └── NettyProxyServer.kt  # Example implementation using Netty
├── rule/                  # Rule engine components
│   ├── Rule.kt            # Interface for rules
│   ├── RuleManager.kt
│   ├── DirectRule.kt
│   └── DomainMatcherRule.kt # etc.
├── socket/
│   ├── raw/               # Raw socket abstractions and implementations
│   │   ├── RawTcpSocket.kt  # Interface
│   │   └── NettyRawTcpSocket.kt # Example implementation
│   ├── proxy/             # Client-side proxy protocol handling
│   │   ├── ProxySocket.kt   # Interface or abstract class
│   │   ├── HttpProxySocket.kt
│   │   └── Socks5ProxySocket.kt
│   ├── adapter/           # Server-side outgoing connection handling
│   │   ├── AdapterSocket.kt # Interface or abstract class
│   │   ├── DirectAdapterSocket.kt
│   │   ├── HttpAdapterSocket.kt
│   │   └── Socks5AdapterSocket.kt
│   │   └── factory/         # Adapter factories
│   │       ├── AdapterFactory.kt
│   │       ├── DirectAdapterFactory.kt
│   │       └── HttpAdapterFactory.kt # etc.
├── tunnel/                # Connection orchestration
│   └── Tunnel.kt
└── event/                 # Event/observer system (if implemented with custom observers)
    ├── Event.kt
    └── Observer.kt
```

## 4. Mapping Swift to Kotlin

### 4.1. Swift File/Type to Kotlin File/Type Mapping

| Swift File/Module             | Proposed Kotlin Package         | Kotlin File/Type                                       | Notes                                             |
|-------------------------------|---------------------------------|--------------------------------------------------------|---------------------------------------------------|
| `ConnectSession.swift`        | `core`                          | `ConnectSession.kt` (`data class ConnectSession`)      |                                                   |
| `IPAddress.swift`             | `core`                          | `IpAddress.kt` (`value class IpAddress` or `data class`)|                                                   |
| `Port.swift`                  | `core`                          | `Port.kt` (`value class Port` or `data class`)         |                                                   |
| `RawTCPSocketProtocol.swift`  | `socket.raw`                    | `RawTcpSocket.kt` (`interface RawTcpSocket`)           |                                                   |
| `GCDTCPSocket.swift`          | `socket.raw`                    | `NettyRawTcpSocket.kt` (`class NettyRawTcpSocket`)     | Example, using Netty                                |
| `ProxyServer.swift`           | `proxyserver`                   | `ProxyServer.kt` (`interface ProxyServer`)             |                                                   |
| `GCDProxyServer.swift`        | `proxyserver`                   | `NettyProxyServer.kt` (`class NettyProxyServer`)       | Concrete implementation                             |
| `ProxySocket.swift`           | `socket.proxy`                  | `ProxySocket.kt` (`interface ProxySocket` or `abstract class`) |                                                   |
| `HTTPProxySocket.swift`       | `socket.proxy`                  | `HttpProxySocket.kt` (`class HttpProxySocket`)         | Implements `ProxySocket`                          |
| `SOCKS5ProxySocket.swift`     | `socket.proxy`                  | `Socks5ProxySocket.kt` (`class Socks5ProxySocket`)     | Implements `ProxySocket`                          |
| `Tunnel.swift`                | `tunnel`                        | `Tunnel.kt` (`class Tunnel`)                           |                                                   |
| `RuleManager.swift`           | `rule`                          | `RuleManager.kt` (`class RuleManager`)                 |                                                   |
| `Rule.swift` (and variants)   | `rule`                          | `Rule.kt` (`interface Rule`), `DirectRule.kt`, etc.    |                                                   |
| `AdapterFactory.swift`        | `socket.adapter.factory`        | `AdapterFactory.kt` (`interface AdapterFactory`)       |                                                   |
| (Specific Factories)          | `socket.adapter.factory`        | `DirectAdapterFactory.kt`, etc.                        |                                                   |
| `AdapterSocket.swift`         | `socket.adapter`                | `AdapterSocket.kt` (`interface AdapterSocket` or `abstract class`) |                                                   |
| `DirectAdapter.swift`         | `socket.adapter`                | `DirectAdapterSocket.kt` (`class DirectAdapterSocket`) | Implements `AdapterSocket`                        |
| `HTTPAdapter.swift`           | `socket.adapter`                | `HttpAdapterSocket.kt` (`class HttpAdapterSocket`)     | Implements `AdapterSocket`                        |
| `SOCKS5Adapter.swift`         | `socket.adapter`                | `Socks5AdapterSocket.kt` (`class Socks5AdapterSocket`) | Implements `AdapterSocket`                        |

### 4.2. Conceptual Translation of Swift Design Patterns

*   **Protocols (Swift) -> Interfaces (Kotlin)**:
    *   Swift `protocol`s like `RawTCPSocketProtocol` will directly map to Kotlin `interface`s (e.g., `interface RawTcpSocket`).
    *   Methods in interfaces will become standard function declarations. `suspend` will be used for asynchronous operations.
*   **Classes (Swift) -> Classes (Kotlin)**:
    *   Swift `class`es will map to Kotlin `class`.
    *   Mutability and inheritance will be considered: `open` for inheritable classes, `abstract` for abstract classes, and `final` (default in Kotlin) for non-inheritable classes.
*   **Structs (Swift) -> Data Classes / Value Classes (Kotlin)**:
    *   Swift `struct`s, especially those representing simple data aggregates (like `ConnectSession`, `Port`, `IPAddress`), will map well to Kotlin `data class` for auto-generated `equals()`, `hashCode()`, `toString()`, and `copy()`.
    *   For lightweight wrappers, Kotlin `value class` (inline class) can be considered for performance benefits (e.g., `Port`, `IpAddress` if they wrap a single primitive).
*   **Delegation (Swift) -> Interfaces, Lambdas, or Flow (Kotlin)**:
    *   Swift's delegation pattern (e.g., `TunnelDelegate`, `SocketDelegate`) can be translated in several ways:
        1.  **Kotlin Interfaces**: Define a listener interface (e.g., `TunnelListener`, `SocketListener`) and pass instances of anonymous or concrete implementations.
        2.  **Lambdas/Higher-Order Functions**: For single-method delegates, Kotlin lambdas can be used directly for callbacks.
        3.  **Kotlin Flow/Channel**: For streams of events or asynchronous callbacks (like data received, socket disconnected), Kotlin `Flow` (for cold streams) or `SharedFlow`/`StateFlow` (for hot streams/state representation) or `Channel` (for direct send/receive between coroutines) are more idiomatic and robust replacements for delegate-based callbacks. This is particularly relevant for socket data events.
*   **GCD & Asynchronous Operations (Swift) -> Kotlin Coroutines**:
    *   This is a significant architectural shift. GCD's dispatch queues, groups, and semaphores will be replaced by Kotlin's structured concurrency model.
    *   **`DispatchQueue`**: Replaced by `CoroutineDispatcher` (e.g., `Dispatchers.IO` for network operations, `Dispatchers.Default` for CPU-bound tasks). Custom thread pools can be created if needed.
    *   **Asynchronous functions**: Swift functions with completion handlers will become Kotlin `suspend` functions.
    *   **Serial Queues**: If strict serial execution is needed (as often with GCD delegate queues for synchronizing access to mutable state), this can be achieved with a single-threaded `CoroutineDispatcher` (`Dispatchers.Default.limitedParallelism(1)` or `newSingleThreadContext`) or by using `Mutex` within a coroutine.
    *   **Structured Concurrency**: `CoroutineScope` will be used to manage the lifecycle of coroutines, ensuring that they are properly cancelled when their parent scope is cancelled.
*   **Swift Extensions -> Kotlin Extension Functions**:
    *   Swift `extension`s map directly to Kotlin's extension functions or extension properties, providing similar syntactic sugar for adding functionality to existing classes.
*   **Error Handling (Swift `Error` protocol -> Kotlin `Exception`)**:
    *   Swift's `Error` protocol and `throw`ing functions will map to Kotlin's `Exception` classes and `throw`ing functions. Custom exception classes can be defined for specific error conditions (e.g., `NetworkException`, `ProxyHandshakeException`).
*   **Observer Pattern (Swift custom -> Kotlin Flow/Event Bus)**:
    *   If NEKit's Swift version uses a custom observer pattern (like `ObserverFactory` and `Observer<EventType>`), this can be translated to:
        *   **Kotlin `Flow` or `SharedFlow`**: For broadcasting events from components (e.g., `ProxyServerEvent`, `TunnelEvent`).
        *   **Event Bus**: A dedicated event bus library could be used if cross-component communication is complex and decoupled.

## 5. Key Kotlin Component Design (High-Level)

For each major component:

*   **`RawTcpSocket.kt` (Interface)**
    ```kotlin
    package io.github.hohohahe.nekitkotlin.socket.raw

    import java.nio.ByteBuffer // Or use ByteArray directly

    interface RawTcpSocket {
        val isOpen: Boolean
        val localAddress: String? // Example, may need richer Address type
        val remoteAddress: String? // Example

        suspend fun connect(host: String, port: Int)
        suspend fun read(buffer: ByteBuffer): Int // Returns bytes read or -1 for EOF. ByteBuffer for efficient I/O.
        suspend fun write(buffer: ByteBuffer)    // ByteBuffer for efficient I/O.
        fun close()
        // Optional: Flow for listening to incoming data if the model is more reactive
        // fun incomingData(): Flow<ByteBuffer>
    }
    ```
    *   **Responsibilities**: Abstract low-level TCP operations. `NettyRawTcpSocket` would implement this using Netty's `Channel`.
    *   **Interactions**: Used by `ProxySocket` and `AdapterSocket`.

*   **`ProxyServer.kt` (Interface)**
    ```kotlin
    package io.github.hohohahe.nekitkotlin.proxyserver

    import io.github.hohohahe.nekitkotlin.core.Port
    import kotlinx.coroutines.flow.SharedFlow // For events

    interface ProxyServer {
        val port: Port
        // val address: IpAddress? // If binding to specific address
        // val events: SharedFlow<ProxyServerEvent> // To emit events like new connections, errors

        suspend fun start() // Changed to suspend if startup involves async ops
        fun stop()
    }
    ```
    *   **Responsibilities**: Listen for incoming client connections. Manage server lifecycle.
    *   **Interactions**: Creates `ProxySocket` instances (or delegates to a handler that does). `NettyProxyServer` would use Netty's server bootstrap.

*   **`ProxySocket.kt` (Interface/Abstract Class)**
    ```kotlin
    package io.github.hohohahe.nekitkotlin.socket.proxy

    import io.github.hohohahe.nekitkotlin.core.ConnectSession
    import io.github.hohohahe.nekitkotlin.socket.Socket // Common base for ProxySocket & AdapterSocket
    import kotlinx.coroutines.flow.Flow
    import java.nio.ByteBuffer

    interface ProxySocket : Socket { // Assuming a common Socket interface
        // Flow to emit the ConnectSession once parsed, or null if error
        fun getConnectSession(): Flow<ConnectSession>
        suspend fun respondToSuccess() // Generic success response after adapter is ready
        // Specific error responses might be needed
    }
    ```
    *   **Responsibilities**: Handle client-side protocol (HTTP/SOCKS5). Parse request to produce `ConnectSession`. Relay data to/from `Tunnel`.
    *   **Interactions**: Managed by `Tunnel`. Uses `RawTcpSocket`. Emits `ConnectSession` to `Tunnel`.

*   **`AdapterSocket.kt` (Interface/Abstract Class)**
    ```kotlin
    package io.github.hohohahe.nekitkotlin.socket.adapter

    import io.github.hohohahe.nekitkotlin.core.ConnectSession
    import io.github.hohohahe.nekitkotlin.socket.Socket
    import kotlinx.coroutines.flow.StateFlow // To signal readiness

    interface AdapterSocket : Socket {
        val isReady: StateFlow<Boolean> // To signal when connection and handshake are complete
        suspend fun openSocket(session: ConnectSession)
    }
    ```
    *   **Responsibilities**: Handle server-side connection (direct or via upstream proxy). Perform handshakes. Relay data to/from `Tunnel`.
    *   **Interactions**: Managed by `Tunnel`. Uses `RawTcpSocket`. Signals readiness via `isReady` Flow.

*   **`Tunnel.kt` (Class)**
    ```kotlin
    package io.github.hohohahe.nekitkotlin.tunnel

    import io.github.hohohahe.nekitkotlin.socket.proxy.ProxySocket
    import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket
    import kotlinx.coroutines.CoroutineScope

    class Tunnel(
        private val scope: CoroutineScope, // For launching forwarding coroutines
        private val proxySocket: ProxySocket,
        private val ruleManager: io.github.hohohahe.nekitkotlin.rule.RuleManager
        // AdapterFactory can be resolved via RuleManager
    ) {
        // private var adapterSocket: AdapterSocket? = null // Set after rule matching

        suspend fun open() {
            // 1. Get ConnectSession from proxySocket.getConnectSession().first()
            // 2. Match rule using RuleManager to get AdapterFactory
            // 3. Create AdapterSocket using factory.getAdapterFor(session)
            // 4. Call adapterSocket.openSocket(session)
            // 5. Wait for both proxySocket and adapterSocket to be ready (e.g., adapterSocket.isReady.first { it })
            // 6. proxySocket.respondToSuccess()
            // 7. Launch forwarding coroutines (proxyToAdapter and adapterToProxy) within 'scope'
        }

        fun close() {
            // Close proxySocket and adapterSocket
            // Cancel forwarding coroutines by cancelling 'scope' or specific jobs
        }
    }
    ```
    *   **Responsibilities**: Orchestrate flow between `ProxySocket` and `AdapterSocket`. Manage their lifecycles for a single connection. Use `RuleManager` to select adapter.
    *   **Interactions**: Owns `ProxySocket` and `AdapterSocket`. Uses `RuleManager`.

*   **`RuleManager.kt` (Class)**
    ```kotlin
    package io.github.hohohahe.nekitkotlin.rule

    import io.github.hohohahe.nekitkotlin.core.ConnectSession
    import io.github.hohohahe.nekitkotlin.socket.adapter.factory.AdapterFactory

    class RuleManager(private val rules: List<Rule>) {
        fun match(session: ConnectSession): AdapterFactory {
            // Iterate through rules, return factory from first matching rule
            // Default to a direct factory if no rules match
            return rules.firstNotNullOfOrNull { it.match(session) }
                ?: io.github.hohohahe.nekitkotlin.socket.adapter.factory.DirectAdapterFactory() // Example default
        }
    }
    ```
    *   **Responsibilities**: Select appropriate `AdapterFactory` based on `ConnectSession` and configured rules.

## 6. Identified Kotlin Dependencies

*   **Networking**:
    *   **Netty**: (`io.netty:netty-codec-http`, `io.netty:netty-codec-socks`, `io.netty:netty-handler`, `io.netty:netty-transport-native-epoll` (Linux for performance)).
        *   *Justification*: High-performance, battle-tested, flexible, good support for various protocols including SOCKS and HTTP. Steeper learning curve but powerful.
    *   *(Alternative) Ktor Networking*: (`io.ktor:ktor-network`, `io.ktor:ktor-network-tls`)
        *   *Justification*: More coroutine-idiomatic, simpler API. Might be less performant or flexible for very low-level proxy operations than Netty.
*   **Concurrency**:
    *   `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3` (or latest)
    *   `org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3` (for CompletableFuture integration if needed)
*   **DNS Resolution**:
    *   Can use `Dispatchers.IO` with `java.net.InetAddress.getAllByName(host)`.
    *   If Netty is used, `io.netty.resolver.dns.DnsNameResolver` provides asynchronous DNS resolution.
    *   If Ktor is used, it has built-in DNS resolution capabilities.
*   **Cryptography** (If specific adapters like Shadowsocks are in scope later):
    *   JCA (Java Cryptography Architecture) + BouncyCastle: `org.bouncycastle:bcprov-jdk18on:1.77`
    *   Libsodium JVM bindings: (e.g., `com.github.jnr:jnr-ffi` and a libsodium wrapper)
*   **Configuration Parsing**:
    *   `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0` (for JSON if YAML is not strictly needed, or convert YAML to JSON first)
    *   Jackson with Kotlin module: `com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2` and `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2` (for YAML).
*   **Logging**:
    *   SLF4J API: `org.slf4j:slf4j-api:2.0.9`
    *   Logback Implementation: `ch.qos.logback:logback-classic:1.4.11`
    *   Kotlin Logging Wrapper (optional but convenient): `io.github.microutils:kotlin-logging-jvm:3.0.5`

## 7. Phased Translation Approach (Recommendation)

*   **Phase 1: Project Setup & Core Abstractions**
    *   Gradle project setup with Kotlin and necessary dependencies (Coroutines, Netty/Ktor basics).
    *   Define core data types: `ConnectSession`, `IpAddress`, `Port`.
    *   Define `RawTcpSocket` interface. Implement a basic version (e.g., `NettyRawTcpSocket` or one using `java.nio.channels.AsynchronousSocketChannel` with coroutine wrappers). Basic unit tests for socket operations.
*   **Phase 2: Direct Connection Path**
    *   Implement `DirectAdapterSocket` using `RawTcpSocket`.
    *   Implement a minimal `ProxySocket` (base class/interface, perhaps a dummy implementation that yields a predefined `ConnectSession`).
    *   Implement the `Tunnel` logic for direct connections, focusing on orchestrating the `ProxySocket` and `DirectAdapterSocket`.
    *   Basic end-to-end test: manually create `Tunnel` with `ProxySocket` and `DirectAdapterSocket` to connect to a target.
*   **Phase 3: Rule Engine**
    *   Define `Rule` interface and `AdapterFactory` interface.
    *   Implement `RuleManager`.
    *   Implement `DirectRule` and `DirectAdapterFactory`.
    *   Test `RuleManager`'s ability to select `DirectAdapterFactory`.
*   **Phase 4: Client-Side Protocol Parsing**
    *   Implement `HttpProxySocket` focusing on parsing HTTP requests (especially CONNECT) to create `ConnectSession`.
    *   Implement `Socks5ProxySocket` focusing on SOCKS5 handshake and request parsing to create `ConnectSession`.
    *   Unit tests for parsing logic in both.
*   **Phase 5: Upstream Proxy Adapters**
    *   Implement `HttpAdapterSocket` (connecting to upstream HTTP proxy, handling CONNECT handshake).
    *   Implement `Socks5AdapterSocket` (connecting to upstream SOCKS5 proxy, handling SOCKS5 handshake).
    *   Implement corresponding `HttpAdapterFactory` and `Socks5AdapterFactory`.
    *   Test these adapters individually.
*   **Phase 6: Proxy Server Implementation**
    *   Implement `ProxyServer` interface (e.g., `NettyProxyServer`). This will listen for connections, create the appropriate `ProxySocket` (`HttpProxySocket` or `Socks5ProxySocket` based on port or initial bytes), and then create and run a `Tunnel`.
    *   Integration testing with actual clients (curl, browser) through the `NettyProxyServer`.
*   **Phase 7: Configuration Loading**
    *   Implement configuration data classes.
    *   Add logic to parse a configuration file (e.g., YAML or JSON) to set up `ProxyServer` instances, rules, and adapter factories.
*   **Phase 8: Cryptographic Adapters** (If in scope)
    *   Implement adapters requiring encryption (e.g., Shadowsocks), including necessary crypto dependencies.
*   **Phase 9: Testing, Refinement & Documentation**
    *   Comprehensive integration testing.
    *   Performance benchmarking and tuning.
    *   Refine API design for idiomatic Kotlin usage.
    *   Add KDoc documentation.

## 8. Potential Challenges & Considerations

*   **Concurrency Model Nuances**:
    *   Translating GCD's specific queue semantics (especially serial delegate queues ensuring thread safety for mutable state) requires careful design with Kotlin coroutines. Using `Mutex` or single-threaded dispatchers (`Dispatchers.Default.limitedParallelism(1)`) for state synchronization in critical sections will be essential.
    *   Ensuring non-blocking I/O throughout is key. All socket operations must be `suspend` functions or use callbacks that integrate with the coroutine world (e.g., Netty's `ChannelFuture` converted to `suspendCancellableCoroutine`).
*   **API Design**:
    *   Strive for idiomatic Kotlin. This means preferring `Flow` for streams of events over listener interfaces where appropriate, using sealed classes for representing distinct states (e.g., socket status, proxy parsing states), and leveraging top-level functions and extension functions for utilities.
    *   The choice between interfaces with concrete implementations vs. abstract base classes needs consideration for flexibility and code sharing.
*   **Performance**:
    *   The choice of networking library (Netty vs. Ktor vs. raw NIO) will have performance implications. Netty is generally more performant for high-load scenarios but is more complex.
    *   Object pooling (e.g., for `ByteBuffer`s) might be necessary if performance profiling indicates high GC pressure.
    *   Benchmarking against the original Swift NEKit (if possible on a comparable platform/scenario) and other proxy solutions would be beneficial.
*   **Testing**:
    *   Unit testing individual components (parsers, rule logic) should be straightforward.
    *   Integration testing the proxy server will require setting up mock clients and servers, or testing against real network services. Libraries like `kotlinx-coroutines-test` will be vital for testing coroutine-based logic.
    *   Testing different rule combinations and adapter types will be crucial.
*   **Resource Management**:
    *   Ensuring sockets, channels, and other resources (like Netty `ByteBuf`s if used) are closed correctly is critical to prevent leaks. Structured concurrency helps, but explicit `close()` or `try-with-resources` (Kotlin's `use{}` function) patterns are still necessary. Netty requires careful handling of `ByteBuf` reference counting.
*   **Backpressure**: If using `Flow` for data streams, handling backpressure correctly is important to prevent out-of-memory errors if one side of the proxy is much faster than the other.

## 9. Conclusion

Translating NEKit's core proxy functionalities from Swift to Kotlin (JVM) is a significant but achievable undertaking. This plan proposes a structured approach, starting with core abstractions and progressively building out features. By leveraging Kotlin's powerful concurrency features with Coroutines and a robust networking library like Netty, the resulting `nekit-kotlin` library can offer a modern, performant, and platform-agnostic (JVM-first) solution for advanced network proxying. The key benefits include access to the rich JVM ecosystem, improved maintainability through Kotlin's language features, and the potential for future expansion to other platforms via Kotlin Multiplatform. Careful attention to concurrency, API design, and resource management will be paramount to the success of this translation.
