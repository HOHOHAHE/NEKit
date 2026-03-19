import socket
import struct

# --- 設定區 ---
PROXY_ADDR = "192.168.212.67"
PROXY_PORT = 57600
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

        # SOCKS5 回應格式 (RFC 1928):
        # +----+-----+-------+------+----------+----------+
        # |VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
        # +----+-----+-------+------+----------+----------+
        # | 1  |  1  |  1    |  1   | Variable |    2     |
        # +----+-----+-------+------+----------+----------+
        # ATYP=0x01 → IPv4 (4 bytes), ATYP=0x03 → Domain, ATYP=0x04 → IPv6 (16 bytes)

        # 先讀取前 4 個位元組: VER + REP + RSV + ATYP
        header = s.recv(4)
        if len(header) < 4 or header[1] != 0x00:
            print(f"[-] 代理伺服器不支援 UDP ASSOCIATE 或發生錯誤: {header}")
            return

        atyp = header[3]

        bind_addr = None
        port_data = None

        if atyp == 0x01:
            # IPv4: 讀取 4 bytes 地址 + 2 bytes 端口
            addr_data = s.recv(4)
            bind_addr = socket.inet_ntoa(addr_data)
            port_data = s.recv(2)
        elif atyp == 0x03:
            # Domain: 先讀 1 byte 長度，再讀取域名 + 2 bytes 端口
            domain_len = s.recv(1)[0]
            domain_data = s.recv(domain_len)
            bind_addr = domain_data.decode("utf-8")
            port_data = s.recv(2)
        elif atyp == 0x04:
            # IPv6: 讀取 16 bytes 地址 + 2 bytes 端口
            addr_data = s.recv(16)
            bind_addr = socket.inet_ntop(socket.AF_INET6, addr_data)
            port_data = s.recv(2)
        else:
            print(f"[-] 未知的地址類型 ATYP={atyp}")
            return

        bind_port = struct.unpack(">H", port_data)[0]

        # 如果伺服器回傳 0.0.0.0，代表使用原本的代理伺服器 IP
        if bind_addr in ("0.0.0.0", "::"):
            bind_addr = PROXY_ADDR

        print(f"[+] 成功建立關聯！UDP 轉發位址: {bind_addr}:{bind_port}")

        # 4. 透過 UDP 埠號發送資料
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        
        # SOCKS5 UDP 封裝標頭: RSV(2) + FRAG(1) + ATYP(1) + DST.ADDR + DST.PORT
        # 這裡假設目標是 IPv4 (ATYP=0x01)
        header = b"\x00\x00\x00\x01" + socket.inet_aton(TARGET_ADDR) + struct.pack(">H", TARGET_PORT)
        udp_sock.sendto(header + PAYLOAD, (bind_addr, bind_port))

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
