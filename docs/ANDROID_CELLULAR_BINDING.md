# Android Cellular 網路綁定整合指南

本指南說明如何在 Android 應用程式端，利用 NEKit 提供的 `SocketBinder`，將 Proxy 的 Socket 連線強制綁定至 Cellular (4G/5G) 網路。

## 1. 背景說明

NEKit 的核心網路模組 (`src_kt`) 是一個與平台無關的純 Kotlin 模組，不依賴 Android SDK。
因此我們無法在核心層級直接呼叫 `android.net.Network` 相關的 API 來綁定 Socket。

為了解決這個問題，NEKit 提供了一個 `SocketBinder` 介面，允許 Android App 將網路綁定邏輯 (Dependency Injection) 注入至 Proxy。

## 2. 實作步驟

### A. 實作 Android 端的 Connectivity Callback

在您的 Android 專案中（例如撰寫在 `MainActivity` 或某個背景 `Service` 中），使用 `ConnectivityManager` 監聽 Cellular 網路的可用性。

```kotlin
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.nekit.RawSocket.SocketBinder
import com.example.nekit.RawSocket.SocketBinderFactory
import com.example.nekit.Config.NetworkInterfaceType
import com.example.nekit.Config.GlobalNetworkManager

@RequiresApi(Build.VERSION_CODES.M) // bindSocket 需要 API 23 以上
class CellularNetworkManager(private val context: Context) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun startListeningToCellular() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
            
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                println("✅ Cellular network is available: $network")
                
                // 【關鍵 1】實作 SocketBinder，在其中呼叫 Android 原生的 network.bindSocket()
                val androidBinder = object : SocketBinder {
                    override fun bindSocket(socket: java.net.Socket) {
                        network.bindSocket(socket)
                    }

                    override fun bindDatagramSocket(socket: java.net.DatagramSocket) {
                        network.bindSocket(socket)
                    }
                }
                
                // 【關鍵 2】將這個實作好的 Binder 註冊進入 NEKit 模組
                SocketBinderFactory.cellularBinder = androidBinder
                
                // 【關鍵 3】(選用) 告訴 NEKit 接下來的連線預設都要使用 Cellular
                GlobalNetworkManager.currentActiveInterface = NetworkInterfaceType.CELLULAR
            }
            
            override fun onLost(network: Network) {
                super.onLost(network)
                println("❌ Cellular network was lost.")
                
                // 清除 Binder，以免嘗試使用失效的 Network 物件綁定
                SocketBinderFactory.cellularBinder = null
                
                // 恢復為預設介面 (Wi-Fi)
                GlobalNetworkManager.currentActiveInterface = NetworkInterfaceType.DEFAULT
            }
        }
        
        // 開始監聽 Cellular 網路變化
        connectivityManager.requestNetwork(request, networkCallback)
    }
}
```

### B. 初始化呼叫

在應用程式啟動時，呼叫上述程式碼開始監聽：

```kotlin
val cellularManager = CellularNetworkManager(applicationContext)
cellularManager.startListeningToCellular()
```

## 3. 它是如何運作的？

1. 當 `GlobalNetworkManager.currentActiveInterface` 設定為 `NetworkInterfaceType.CELLULAR` 時，NEKit 的 `RawSocketFactory` 知道要分配 `RawCellularTCPSocket` 和 `RawCellularUDPSocket`。
2. 這些 Cellular Sockets 在準備要進行 `connect()` 或 `bind()` 之前，會回頭向 `SocketBinderFactory` 取得已經被 Android App 註冊好的 `cellularBinder`。
3. 接觸到真正的實體 Socket 時，控制權會暫時回到 Android 端，呼叫 `network.bindSocket(socket)`。
4. 最終，Socket 才去連線外網，此時這條實體 Socket 已經被加上了強迫走 Cellular 路由的標記，達到強制切換網路的效果。
