import socket
import struct

# --- 設定區 ---
PROXY_ADDR = "127.0.0.1"
PROXY_PORT = 1080
TARGET_ADDR = "8.8.8.8"  # 測試目標，例如 Google DNS
TARGET_PORT = 53
PAYLOAD = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x05baidu\x03com\x00\x00\x01\x00\x01" # DNS Query 範例
# --------------

def test_socks5_udp():
    try:
        # 1. 建立 TCP 連線至代理伺服器 (控制通道)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((PROXY_ADDR, PROXY_PORT))

        # 2. 認證 (無密碼模式)
        s.sendall(b"\x05\x01\x00")
        if s.recv(2) != b"\x05\x00":
            print("[-] 認證失敗")
            return

        # 3. 發送 UDP ASSOCIATE 請求
        # 告訴代理伺服器：我要準備發送 UDP 封包了
        s.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        res = s.recv(10)
        
        if len(res) < 2 or res[1] != 0x00:
            print(f"[-] 代理伺服器不支援 UDP ASSOCIATE 或發生錯誤: {res}")
            return

        # 取得代理伺服器提供的 UDP 轉發埠號
        # 這裡會跳過回應中的地址資訊，直接抓取後兩位元組作為 Port
        bind_port = struct.unpack(">H", res[-2:])[0]
        print(f"[+] 成功建立關聯！代理伺服器 UDP 轉發端口: {bind_port}")

        # 4. 透過 UDP 埠號發送資料
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # SOCKS5 UDP 封裝標頭: RSV(2) + FRAG(1) + ATYP(1) + DST.ADDR + DST.PORT
        # 這裡假設目標是 IPv4 (ATYP=0x01)
        header = b"\x00\x00\x00\x01" + socket.inet_aton(TARGET_ADDR) + struct.pack(">H", TARGET_PORT)
        udp_sock.sendto(header + PAYLOAD, (PROXY_ADDR, bind_port))

        # 5. 嘗試接收回傳
        udp_sock.settimeout(5.0)
        data, addr = udp_sock.recvfrom(2048)
        print(f"[+] 收到回傳！來自 {addr}，長度: {len(data)} bytes")
        print(f"[+] 測試成功：你的 SOCKS5 代理能正常轉發 UDP 封包。")

    except Exception as e:
        print(f"[!] 發生錯誤: {e}")
    finally:
        s.close()

if __name__ == "__main__":
    test_socks5_udp()
