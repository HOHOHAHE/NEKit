# NEKit Apple 平台本地測試指南

本文件說明如何在 macOS 與 iOS 平台上使用 Swift Package Manager (SPM) 對 NEKit 進行本地端測試，讓您無須直接部署到 iOS 裝置或模擬器，即可驗證 Proxy Server 的核心邏輯與網路轉發功能。

## 執行本地 Proxy Server

在 NEKit 專案的根目錄下，開啟終端機並執行以下指令：

```bash
swift run NEKitLocalRun
```

### 說明

`swift run NEKitLocalRun` 會觸發 Swift Package Manager 編譯 `Package.swift` 中定義的 `NEKitLocalRun` 執行檔目標 (Executable Target)。

此執行檔的程式碼位於：
`Sources/NEKitLocalRun/main.swift`

執行後，它會自動呼叫 `NEKit` 核心函式庫來啟動一個本地端的 HTTP Proxy 代理伺服器（預設監聽 `127.0.0.1:9090`）。這與 Android 目錄下執行 `./gradlew runLocal` 以進行測試的目的相同。

## 測試 Proxy 服務

當本地 Proxy 服務啟動後，終端機會顯示類似以下的輸出訊息：

```
Local HTTP Proxy started on 127.0.0.1:9090
You can test it with `curl -x http://127.0.0.1:9090 http://example.com`
```

您可以開啟另一個新的終端機視窗，並透過 `curl` 指令透過代理發送 HTTP 請求來驗證 Proxy 服務是否正常運作：

```bash
curl -x http://127.0.0.1:9090 http://example.com
```

如果 Proxy 設定正確，您應該能在終端機看到 `example.com` 頁面的 HTML 原始碼回傳。

## 停止服務

若要結束本地 Proxy 代理伺服器，只需在執行該服務的終端機畫面中按下 `Ctrl + C` 即可終止程序。

## 在您的 iOS 專案中整合 NEKit

透過 Swift Package Manager (SPM)，您可以非常輕鬆地將 NEKit 整合到現有的 iOS 應用程式專案中。

### 步驟 1：使用 Xcode 加入套件

1. 開啟您的 iOS 專案。
2. 在選單列中點選 **File** > **Add Package Dependencies...**（新增套件相依性）。
3. 如果您已經將此 NEKit 專案推送至 GitHub 或其他遠端儲存庫，在搜尋框中輸入遠端 URL（如：`https://github.com/your-username/NEKit.git`）。
   - *（如果您只是在本地端開發，可以直接從 Finder 拖曳 NEKit 資料夾到您的 Xcode 專案導覽列 (Project Navigator) 中，或者在輸入欄直接填寫這包程式碼的本地絕對路徑 `file:///path/to/NEKit`）。*
4. 設定 Dependency Rule（相依性規則，例如：`Up to Next Major Version` 或特定的 Branch/Commit），然後點擊 **Add Package** 取回套件。
5. 確保在 "Add to Target" 的選項中勾選您的 iOS 主應用程式目標 (App Target)，如果您有另外建立 Network Extension (例如 Packet Tunnel Provider Target)，也請為其勾選 `NEKit` 函式庫，然後點擊確認。

### 步驟 2：在程式碼中引入與使用

在需要使用代理功能的地方引入 `NEKit`：

```swift
import NEKit

class ProxyManager {
    static let shared = ProxyManager()
    
    func startProxy() {
        // 範例：啟動一個本地 Server 或是進行 Proxy 的初始化設定
        // 您可以參照 Sources/NEKitLocalRun/main.swift 的使用方式進行您的 iOS 專案串接
    }
}
```

### 相關權限設定注意事項

*   如果您的 iOS 應用程式準備建立真正的全域 VPN (IPStack/Proxy)，您的 App ID 必須在 Apple Developer 控制台上開啟 **Network Extension** (Packet Tunnel) 的授權。
*   在您的 Xcode 專案設定中，請務必新增所需的 Capabilities (功能)，並設定正確的 `.entitlements` 檔案。
