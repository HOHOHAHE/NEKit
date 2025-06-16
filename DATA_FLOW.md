# NEKit Data Flow

This document outlines the data flow within the NEKit library.

## High-Level Overview

NEKit acts as a networking proxy library. It intercepts network connections, applies rules to them, and then forwards them through various "adapters" based on those rules.

## Detailed Data Flow

1.  **Initialization:**
    *   An application (e.g., `NEKitDemo`) initializes NEKit.
    *   A `Configuration` object is created, typically by loading settings from a YAML configuration file. This file defines:
        *   Proxy server settings (e.g., HTTP and SOCKS5 proxy ports).
        *   A list of `AdapterFactory` instances, which create different types of adapters (e.g., direct connection, HTTP proxy, SOCKS5 proxy).
        *   A list of `Rule` instances, which define how to handle different types of connections.
    *   A `RuleManager` is initialized with the configured rules.
    *   Proxy servers (e.g., `GCDHTTPProxyServer`, `GCDSOCKS5ProxyServer`) are started based on the configuration.

2.  **Client Connection:**
    *   A client application connects to one of the NEKit proxy servers (e.g., the HTTP proxy).

3.  **Rule Matching:**
    *   The proxy server creates a `ConnectSession` object representing the client connection.
    *   The `RuleManager` attempts to match the `ConnectSession` against its list of `Rule` instances.
    *   Rules are evaluated in order. Each rule can:
        *   **Match:** If a rule matches, it determines how the connection should be handled.
        *   **Not Match:** If a rule doesn't match, the `RuleManager` moves to the next rule.
        *   **Pass:** Some rules might decide to pass the decision to subsequent rules.

4.  **Adapter Selection and Connection:**
    *   When a rule matches, it provides an `AdapterFactory` associated with that rule.
    *   The `AdapterFactory` is responsible for creating an `AdapterSocket` instance. Examples of `AdapterSocket` subclasses include:
        *   `DirectAdapter`: Connects directly to the destination server.
        *   `HTTPAdapter`: Forwards the connection through an HTTP proxy.
        *   `SOCKS5Adapter`: Forwards the connection through a SOCKS5 proxy.
        *   `ShadowsocksAdapter`: Forwards the connection through a Shadowsocks proxy.
    *   The selected `AdapterSocket` then:
        *   Establishes a connection to the actual destination server (or the next hop proxy). This usually involves creating and managing a `RawTCPSocketProtocol` (a low-level TCP socket).
        *   Handles the data transfer between the client and the destination server.

5.  **Data Transfer:**
    *   Once the connection is established by the `AdapterSocket`, data is relayed:
        *   Data received from the client by the NEKit proxy server is written to the `AdapterSocket`.
        *   The `AdapterSocket` sends this data to the destination server (or next hop proxy).
        *   Data received from the destination server by the `AdapterSocket` is passed back to the NEKit proxy server.
        *   The NEKit proxy server sends this data back to the client.

6.  **Disconnection:**
    *   When the client or server closes the connection, or if an error occurs, the `AdapterSocket` handles the disconnection process, and the NEKit proxy server closes its connection with the client.

## Key Components:

*   **`Configuration`**: Loads and holds all settings, including rules and adapter configurations.
*   **`RuleManager`**: Manages the list of rules and performs matching.
*   **`Rule`**: Defines criteria for matching connections and specifies which adapter to use.
*   **`AdapterFactory`**: Creates instances of `AdapterSocket`.
*   **`AdapterSocket`**: Abstract base class for different connection handling strategies (direct, proxy, etc.). It manages the underlying `RawTCPSocketProtocol`.
*   **`RawTCPSocketProtocol`**: Represents the low-level TCP socket used for actual network communication.
*   **Proxy Servers (e.g., `GCDHTTPProxyServer`, `GCDSOCKS5ProxyServer`)**: Listen for incoming client connections.
*   **`ConnectSession`**: Represents an active client connection being processed by NEKit.

This flow allows NEKit to be highly configurable and support various proxy protocols and routing rules.
