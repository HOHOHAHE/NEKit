## 1. 總覽 (Overview)

本報告總結了我將 NEKit Swift 專案 (`src` 目錄) 轉換為 Kotlin (`src_kt` 目錄) 的過程。轉換的主要目標是將原始程式碼的結構和功能平移到 JVM 平台，同時採用 Kotlin 的慣用寫法和標準 JVM 生態系統中的函式庫。

由於環境限制，**我未能編譯和測試所有已轉換的 Kotlin 程式碼**。因此，本報告主要關注結構和概念上的轉換策略。

## 2. Swift 檔案/類型 到 Kotlin 檔案/類型 的映射 (Swift File/Type to Kotlin File/Type Mapping)

總體原則是保持原始 `src` 目錄的結構，並將 Swift 檔案 (`.swift`) 一對一轉換為相應路徑下的 Kotlin 檔案 (`.kt`)。

| Swift 概念         | Kotlin 對應                                     | 備註                                                                                                                               |
| :----------------- | :---------------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------- |
| `class`            | `class`, `open class`                           | Kotlin class 預設是 final，因此需要 `open` 關鍵字以允許繼承。                                                                             |
| `struct`           | `data class`, `class`, `@JvmInline value class` | `data class` 用於純資料容器；普通 `class` 用於有複雜行為的結構；`value class` 用於輕量級包裝器 (如 `Port.kt`)。                               |
| `enum`             | `enum class`, `sealed class`                    | Swift enum 若帶有關聯值 (associated values)，通常轉換為 Kotlin `sealed class` 及其嵌套的 `data class` 子類 (例如 `src_kt/Event/Event/` 中的事件類型)。 |
| `protocol`         | `interface`                                     |                                                                                                                                    |
| `extension`        | Extension Functions / Properties                | Kotlin 的擴展函數/屬性提供了類似的功能。                                                                                                 |
| `typealias`        | `typealias`                                     | 語法和功能相似。                                                                                                                         |
| `(T) -> U` (Closure) | `(T) -> U` (Lambda)                             | Kotlin lambda 的語法和用法與 Swift closure 非常接近。                                                                                   |
| `Data`             | `ByteArray`, `java.nio.ByteBuffer`              | `ByteArray` 用於原始字節數據；`ByteBuffer` 用於更複雜的二進制數據操作和 I/O。                                                                 |
| `Error` (protocol) | `Throwable`, custom `Exception` classes         | Swift `Error` enum 通常轉換為繼承自 `Exception` 的 `sealed class` 層次結構 (例如 `ConfigurationException.kt`)。                         |
| `Int`, `String`, etc. | `Int`, `String`, etc.                           | 基本數據類型通常有直接對應。Swift 的無符號整數 (`UInt`, `UShort`) 轉換為 Kotlin 的實驗性無符號類型或用標準整數類型處理，並注意位運算。                 |
| `Date`             | `java.util.Date`, `java.time.*` (JSR-310)       | 通常推薦使用 `java.time` API。                                                                                                        |
| `URL`              | `java.net.URL`, `java.net.URI`                  |                                                                                                                                    |
| `DispatchQueue`    | Kotlin Coroutines (`CoroutineScope`, `Dispatchers`, `Mutex`, `Channel`) | 見下文設計模式轉換部分。                                                                                                             |
| `Array<T>`         | `List<T>`, `MutableList<T>`                     | Kotlin 區分可變和不可變集合。                                                                                                              |
| `Dictionary<K,V>`  | `Map<K,V>`, `MutableMap<K,V>`                   | 同上。                                                                                                                               |
| `Optional<T>` (`T?`) | Nullable Types (`T?`)                           | Kotlin 的 nullable types 提供了類似的空安全機制。                                                                                           |

**主要模組的檔案映射範例:**
*   `src/Utils/IPAddress.swift` -> `src_kt/Utils/IPAddress.kt`
*   `src/Crypto/CCCrypto.swift` -> `src_kt/Crypto/CCCrypto.kt` (後續重構為 `CCCryptoAdapter.kt`)
*   `src/IPStack/TUNInterface.swift` -> `src_kt/IPStack/TUNInterface.kt`
*   `src/Socket/AdapterSocket/Shadowsocks/ShadowsocksAdapter.swift` -> `src_kt/Socket/AdapterSocket/Shadowsocks/ShadowsocksAdapter.kt`

## 3. Swift 設計模式的概念性轉換 (Conceptual Translation of Swift Design Patterns)

| Swift 設計模式/特性         | Kotlin 轉換策略                                                                                                                                                              | 範例/備註                                                                                                                                      |
| :-------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------- |
| **Delegation**              | Kotlin Interfaces and Delegate Properties                                                                                                                                    | Swift `protocol XDelegate: class` 通常轉換為 Kotlin `interface XDelegate`。`weak var delegate: XDelegate?` 轉換為 `var delegate: WeakReference<XDelegate?>?`。 |
| **Optionals & Nil Handling**| Kotlin Nullable Types (`T?`), Safe Calls (`?.`), Elvis Operator (`?:`), Not-Null Assertions (`!!`)                                                                             | `guard let ... else` 結構通常轉換為 `val x = y ?: return` 或 `val x = y ?: throw Exception()`。`if let` 轉換為 `?.let { ... }` 或 `if (x != null) { ... }`。 |
| **Error Handling (`throw`, `try`, `catch`)** | Kotlin Exceptions (`throw`, `try-catch-finally`)                                                                                                                             | Swift `Error` enum 轉換為 Kotlin `sealed class MyException : Exception()`。`Result<T, Error>` 模式可通過標準 Kotlin `Result<T>` 或自定義密封類實現。           |
| **Grand Central Dispatch (GCD)** | **Kotlin Coroutines**: `CoroutineScope`, `Dispatchers` (`IO`, `Default`, `Main` - 需安卓環境), `launch`, `async`, `withContext`, `Mutex`, `Channel`, `Flow`                                | GCD 的序列隊列 (serial queue) 可用 `newSingleThreadContext()` 或 `Mutex` 實現同步。GCD 的並發隊列 (concurrent queue) 可用 `Dispatchers.IO` 或 `Dispatchers.Default`。`DispatchQueue.asyncAfter` 用 `delay()`。 |
| **Structs vs. Classes**     | `data class` (for value semantics, immutability), `class`                                                                                                                      | Swift struct 的值語義在 Kotlin 中常用 `data class` 的 `copy()` 方法模擬，或堅持不可變性。                                                              |
| **Lazy Initialization**     | `by lazy {}` delegate property                                                                                                                                               | `lazy var x: T = { ... }()` 轉換為 `val x: T by lazy { ... }`。                                                                                   |
| **Singletons**              | `object` declarations                                                                                                                                                        | Swift `static let shared = MyClass()` 轉換為 Kotlin `object MySingleton`。                                                                         |
| **Computed Properties**     | Custom Getters/Setters for properties                                                                                                                                        | 語法相似。                                                                                                                                     |
| **Property Observers (`willSet`, `didSet`)** | Custom Setters with backing fields, or `Delegates.observable`                                                                                                                  | 較複雜，有時需要手動實現類似邏輯。                                                                                                                     |
| **Automatic Reference Counting (ARC)** | JVM Garbage Collection                                                                                                                                                       | 開發者通常不直接管理記憶體，但需注意 `WeakReference` 以避免內存洩漏 (例如在 delegate 中)。                                                                     |
| **Failable Initializers (`init?`)** | Companion object factory methods returning `T?`, or private constructors with public factory methods.                                                                  | 例如 `ConnectSession.kt` 的實現。                                                                                                                     |
| **Enums with Associated Values** | `sealed class` with nested `data class` subtypes                                                                                                                             | 例如 `src_kt/Event/Event/` 中的事件定義。                                                                                                        |

## 4. 函式庫替代方案 (Library Replacement Strategies)

轉換過程中，許多 Apple 平台特有的框架和函式庫需要替換為 JVM 生態系統中的等效方案。

| Swift/Cocoa 函式庫/框架     | JVM/Kotlin 替代方案                                                                                                                               | 狀態/備註                                                                                                                                                                                                       |
| :-------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------ | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Foundation** (Data, URL, String, etc.) | `java.io`, `java.nio`, `java.net`, Kotlin stdlib extensions                                                                                     | 大部分基礎功能有直接或間接對應。`Data` -> `ByteArray`/`ByteBuffer`。                                                                                                                                                 |
| **Network.framework** (`NWConnection`, `NWListener`, `NWUDPSession`) | **Netty** (`io.netty:netty-all`)                                                                                                                  | 已完成結構性替換。`NWTCPSocket.kt`, `NWUDPSocket.kt` 的佔位符被基於 Netty 的 `NettyRawTCPClientSocket.kt`, `NettyAcceptedRawSocketAdapter.kt`, `NettyRawUDPSocket.kt` 取代。                                     |
| **CocoaAsyncSocket** (`GCDAsyncSocket`) | **Netty** (`io.netty:netty-all`)                                                                                                                  | `GCDTCPSocket.kt` 的佔位符被 Netty 實現取代。`GCDProxyServer.kt` 中的監聽 socket 也改用 Netty。                                                                                                                     |
| **CommonCrypto** (`CCCryptor`, `CCHmac`, `CC_MD5`) | **JCE (Java Cryptography Extension)**, **BouncyCastle** (`org.bouncycastle:bcprov-jdk18on`)                                                     | JCE 用於標準演算法 (AES, SHA, HMAC, MD5)。BouncyCastle 用於 JCE 未內建或需特定實現的演算法 (如 CAST5, ChaCha20, Salsa20)。`CCCryptoAdapter.kt` 和 `JceStreamCipherAdapter.kt` 已實現。BouncyCastle 已作為 provider 添加。 |
| **Libsodium** (Swift wrapper) | **JNA + Native Libsodium** (或 BouncyCastle for some ciphers)                                                                                     | `SodiumStreamCrypto.kt` (JNI 佔位符) 已被 `JceStreamCipherAdapter.kt` (使用 BouncyCastle) 取代，以提供 ChaCha20/Salsa20 功能。若仍需 Libsodium 特定功能，則需 JNI/JNA + 原生 Libsodium 庫。`Libsodium.kt` 仍為 JNI 初始化佔位符。 |
| **YAML Parsing** (e.g., Yams.swift) | **Jackson Dataformat YAML** (`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`)                                                      | 已在 `src_kt/Config/` 中整合 Jackson YAML，取代了原有的 `YamlNode` 佔位符。                                                                                                                                      |
| **GeoIP/MMDB** (e.g., MMDB.swift) | **MaxMind GeoIP2-java** (`com.maxmind.geoip2:geoip2`)                                                                                           | 已在 `src_kt/GeoIP/GeoIP.kt` 中整合 MaxMind GeoIP2，取代了佔位符。                                                                                                                                              |
| **CocoaLumberjack** (Logging) | **SLF4J API** (`org.slf4j:slf4j-api`) + **Logback Classic** (`ch.qos.logback:logback-classic`)                                                      | 已在整個 `src_kt` 程式碼庫中替換 `println` 等為 SLF4J 日誌調用。                                                                                                                                                    |
| **TUN/TAP Device Interaction** | **JNA + Native OS calls / C helper library**                                                                                                      | 已定義 `TunDeviceInterface.kt` 和 JNA 結構性映射 `JnaTunDevice.kt`。實際功能需依賴特定平台的原生實現。                                                                                                                  |
| **tun2socks Integration** (Native library) | **JNA + Native tun2socks library**                                                                                                                | 已定義 `LibTun2Socks.kt` (包含相關介面) 和 JNA 結構性映射 `JnaLibTun2SocksMappings.kt`。實際功能需依賴特定 `tun2socks` 庫的 C API 和原生庫。                                                                         |

## 5. 主要挑戰和解決方案 (Key Challenges and Solutions)

*   **異步程式設計 (Asynchronous Programming)**:
    *   **挑戰**: Swift 大量使用 GCD (Dispatch Queues, groups, semaphores) 和閉包回調。
    *   **解決方案**: 全面採用 Kotlin Coroutines。`DispatchQueue.async` -> `scope.launch`；`DispatchQueue.sync` -> `runBlocking` (謹慎使用) 或 `Mutex.withLock`；回調 -> `suspend` 函數或 coroutine continuations。
*   **平台依賴的 API (Platform-Specific APIs)**:
    *   **挑戰**: 網路 (Network.framework, CocoaAsyncSocket), 加密 (CommonCrypto), TUN/TAP。
    *   **解決方案**: 採用成熟的 Java/Kotlin 函式庫 (Netty, JCE/BouncyCastle) 和 JNA 技術橋接原生代碼。首先定義 Kotlin 介面，然後逐步實現或替換佔位符。
*   **記憶體管理 (Memory Management)**:
    *   **挑戰**: Swift ARC 與 Kotlin/JVM GC 的差異。特別是 delegate 的循環引用。
    *   **解決方案**: 在 Kotlin 中對 delegates 使用 `WeakReference` 來避免循環引用導致的內存洩漏。
*   **錯誤處理 (Error Handling)**:
    *   **挑戰**: Swift `Error` protocol 和 `throws` 與 Java/Kotlin checked/unchecked exceptions 的差異。
    *   **解決方案**: Swift `Error` enums 轉換為繼承 `Exception` 的 `sealed class` 層次。`do-catch` 轉換為 `try-catch`。
*   **環境限制 (Environmental Limitations)**:
    *   **挑戰**: 執行環境對單個命令可影響的文件數量有限制，導致我無法進行完整編譯和測試。
    *   **解決方案 (部分)**: 我採用了更小粒度的、模塊化的方式進行重構和修改，但最終仍無法繞過此限制進行完整構建。

## 6. 未解決的問題和後續工作 (Unresolved Issues and Next Steps)

*   **編譯和測試 (Compilation and Testing)**: 由於環境限制，我**未能成功編譯**整個 `src_kt` 程式碼庫。這是首要解決的問題。需要在一個無此限制的環境中：
    1.  解決所有編譯錯誤。
    2.  編寫單元測試和集成測試。
    3.  進行功能性驗證和調試。
*   **JNA 原生庫的實際鏈接和測試 (JNA Native Library Linking & Testing)**:
    *   `JnaTunDevice` 和 `JnaLibTun2Socks` 中的 JNA 映射需要針對目標作業系統和實際使用的原生 TUN/TAP 實現及 `tun2socks` 庫進行驗證和調整 (庫名、函數簽名)。
    *   可能需要一個小型的 C 輔助庫來提供跨平台的穩定原生 API。
*   **完成複雜邏輯 TODOs (Complete Complex Logic TODOs)**:
    *   例如，`ShadowsocksTLSProtocolObfuscater` 中服務器端響應的 HMAC/Ticket 校驗。
    *   其他散落在程式碼中的次要 TODO。
*   **性能分析和優化 (Performance Analysis and Optimization)**: 編譯運行後，針對性地進行。
*   **配置 GeoIP 資料庫路徑 (GeoIP Database Path Configuration)**: `GeoIP.initialize()` 需要一個 `.mmdb` 檔案路徑，該路徑應從主配置文件中讀取並正確傳遞。
*   **主入口點和應用程式打包 (Main Entry Point and Application Packaging)**: `build.gradle.kts` 中設置了 `application` 插件和一個佔位符 `mainClassName`。需要確定實際的應用程式入口點並進行配置。
