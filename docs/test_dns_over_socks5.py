import socket
import struct

PROXY_ADDR = "127.0.0.1"
PROXY_PORT = 1080
TARGET_ADDR = "8.8.8.8"
TARGET_PORT = 53

# DNS Query for google.com (A record)
# Transaction ID: 0x1234
# Flags: 0x0100 (Standard query)
# Questions: 1
# Answer RRs: 0
# Authority RRs: 0
# Additional RRs: 0
# Queries: google.com: type A, class IN
PAYLOAD = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x06google\x03com\x00\x00\x01\x00\x01"

def test_socks5_udp_dns():
    try:
        # TCP Control Connection
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect((PROXY_ADDR, PROXY_PORT))

        # Auth
        s.sendall(b"\x05\x01\x00")
        if s.recv(2) != b"\x05\x00":
            print("[-] 認證失敗")
            return

        # UDP ASSOCIATE
        s.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        res = s.recv(10)
        
        if len(res) < 2 or res[1] != 0x00:
            print(f"[-] 代理伺服器不支援 UDP ASSOCIATE: {res}")
            return

        bind_port = struct.unpack(">H", res[-2:])[0]
        print(f"[+] 取得 UDP 轉發端口: {bind_port}")

        # UDP Send
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_sock.settimeout(5.0)
        
        # SOCKS5 UDP Header: RSV(2) + FRAG(1) + ATYP(1) + DST.ADDR(4) + DST.PORT(2)
        header = b"\x00\x00\x00\x01" + socket.inet_aton(TARGET_ADDR) + struct.pack(">H", TARGET_PORT)
        udp_sock.sendto(header + PAYLOAD, (PROXY_ADDR, bind_port))

        print(f"[+] 透過 UDP 傳送 DNS 查詢給 {TARGET_ADDR}:{TARGET_PORT}...")
        
        data, addr = udp_sock.recvfrom(2048)
        print(f"[+] 收到 UDP 回傳！來源: {addr}, 長度: {len(data)} bytes")
        
        if len(data) < 10:
            print("[-] 回覆太短")
            return
            
        rsv = data[0:2]
        frag = data[2]
        atyp = data[3]
        
        idx = 4
        if atyp == 0x01: # IPv4
            bnd_addr = socket.inet_ntoa(data[idx:idx+4])
            idx += 4
        elif atyp == 0x03: # Domain
            dom_len = data[idx]
            idx += 1
            bnd_addr = data[idx:idx+dom_len].decode()
            idx += dom_len
        elif atyp == 0x04: # IPv6
            bnd_addr = "IPv6"
            idx += 16
        else:
            print(f"[-] 未知的 ATYP: {atyp}")
            return
            
        bnd_port = struct.unpack(">H", data[idx:idx+2])[0]
        idx += 2
        
        dns_response = data[idx:]
        print(f"[+] 解析 SOCKS header -> 標頭標示原始來源 IP: {bnd_addr}, Port: {bnd_port}")
        
        if len(dns_response) > 0:
            print(f"[+] 成功取得 DNS 解析結果 ({len(dns_response)} bytes)")
            tx_id = dns_response[0:2]
            if tx_id == b"\x12\x34":
                print("[+] DNS Transaction ID 一致 (0x1234)")
                flags = struct.unpack(">H", dns_response[2:4])[0]
                if (flags & 0x8000) != 0:
                    print("[+] 確實是一個 DNS 回應 (QR bit set)")
                    answers = struct.unpack(">H", dns_response[6:8])[0]
                    print(f"[+] 包含 {answers} 個 Answer RRs")
                    
                    if answers > 0:
                        print(f"[+] 查詢 google DNS 成功，SOCKS5 UDP 正常工作。")
                    else:
                        print("[-] 沒有 Answer RRs，可能有異常。")
                else:
                    print("[-] 這不是 DNS 回應。")
            else:
                print("[-] Transaction ID 不符！")
        else:
            print("[-] 沒有攜帶 payload 資料。")

    except Exception as e:
        print(f"[!] 錯誤: {e}")
    finally:
        s.close()
        udp_sock.close()

if __name__ == "__main__":
    test_socks5_udp_dns()
