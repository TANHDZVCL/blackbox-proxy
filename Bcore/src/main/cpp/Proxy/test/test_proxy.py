#!/usr/bin/env python3
"""Host test for ProxyCore.

Starts a SOCKS5 proxy and an HTTP CONNECT proxy on loopback. Both record the
destination they were asked for and splice the tunnel to a local origin server
instead, so no real network is needed. Client snippets then run under the
LD_PRELOAD shim and dial public addresses.

Usage: test_proxy.py /path/to/libproxyshim.so
"""
import base64
import os
import socket
import socketserver
import struct
import subprocess
import sys
import threading

SHIM = os.path.abspath(sys.argv[1])
USER, PASS = "alice", "s3cret"
seen = []  # (kind, host, port, authenticated)


def recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("eof")
        data += chunk
    return data


def splice(a, b):
    def pump(src, dst):
        try:
            while True:
                data = src.recv(65536)
                if not data:
                    break
                dst.sendall(data)
        except OSError:
            pass
        finally:
            try:
                dst.shutdown(socket.SHUT_WR)
            except OSError:
                pass
    t = threading.Thread(target=pump, args=(a, b), daemon=True)
    t.start()
    pump(b, a)
    t.join()


class Origin(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.recv(65536)
        body = b"hello-from-origin"
        self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
                             % (len(body), body))


class Socks5(socketserver.BaseRequestHandler):
    require_auth = False

    def handle(self):
        s = self.request
        ver, n = recv_exact(s, 2)
        methods = recv_exact(s, n)
        authed = False
        if self.require_auth:
            if 2 not in methods:
                s.sendall(b"\x05\xff")
                return
            s.sendall(b"\x05\x02")
            _, ulen = recv_exact(s, 2)
            user = recv_exact(s, ulen)
            plen = recv_exact(s, 1)[0]
            pw = recv_exact(s, plen)
            if (user.decode(), pw.decode()) != (USER, PASS):
                s.sendall(b"\x01\x01")
                return
            s.sendall(b"\x01\x00")
            authed = True
        else:
            s.sendall(b"\x05\x00")
        ver, cmd, _, atyp = recv_exact(s, 4)
        if atyp == 1:
            host = socket.inet_ntop(socket.AF_INET, recv_exact(s, 4))
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, recv_exact(s, 16))
        else:
            host = recv_exact(s, recv_exact(s, 1)[0]).decode()
        port = struct.unpack("!H", recv_exact(s, 2))[0]
        seen.append(("socks5", host, port, authed))
        if port == 1:  # test hook: destination refused
            s.sendall(b"\x05\x05\x00\x01" + b"\x00" * 6)
            return
        up = socket.create_connection(("127.0.0.1", ORIGIN_PORT))
        s.sendall(b"\x05\x00\x00\x01" + b"\x00" * 6)
        splice(s, up)


class Socks5Auth(Socks5):
    require_auth = True


class HttpProxy(socketserver.BaseRequestHandler):
    def handle(self):
        s = self.request
        head = b""
        while not head.endswith(b"\r\n\r\n"):
            head += recv_exact(s, 1)
        lines = head.decode().split("\r\n")
        method, target, _ = lines[0].split(" ")
        headers = {l.split(":", 1)[0].lower(): l.split(":", 1)[1].strip() for l in lines[1:] if ":" in l}
        expected = "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode()
        if headers.get("proxy-authorization") != expected:
            s.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
            return
        host, port = target.rsplit(":", 1)
        seen.append(("http", host, int(port), True))
        up = socket.create_connection(("127.0.0.1", ORIGIN_PORT))
        s.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
        splice(s, up)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(handler):
    srv = Server(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv.server_address[1]


ORIGIN_PORT = serve(Origin)
SOCKS_PORT = serve(Socks5)
SOCKS_AUTH_PORT = serve(Socks5Auth)
HTTP_PORT = serve(HttpProxy)

CLIENT_TCP = r"""
import socket, sys
fam = socket.AF_INET6 if ':' in sys.argv[1] else socket.AF_INET
s = socket.socket(fam, socket.SOCK_STREAM)
mode = sys.argv[3]
if mode == 'nonblocking':
    s.setblocking(False)
try:
    s.connect((sys.argv[1], int(sys.argv[2])))
except OSError as e:
    print('ERR', e.errno); sys.exit(0)
assert s.getblocking() == (mode != 'nonblocking'), 'blocking mode changed'
s.setblocking(True)
s.sendall(b'GET / HTTP/1.1\r\nHost: x\r\n\r\n')
data = b''
while True:
    c = s.recv(4096)
    if not c: break
    data += c
print('OK' if data.endswith(b'hello-from-origin') else 'BAD ' + repr(data))
"""

# Resolves a name that does not exist anywhere, then connects to what it got.
CLIENT_DNS = r"""
import socket, sys
try:
    infos = socket.getaddrinfo(sys.argv[1], int(sys.argv[2]), type=socket.SOCK_STREAM)
except socket.gaierror as e:
    print('GAIERR'); sys.exit(0)
fam, typ, proto, _, addr = infos[0]
again = socket.getaddrinfo(sys.argv[1].upper(), int(sys.argv[2]), type=socket.SOCK_STREAM)[0][4]
assert again == addr, 'same name must keep its address'
s = socket.socket(fam, typ, proto)
try:
    s.connect(addr)
except OSError as e:
    print('ERR', e.errno); sys.exit(0)
s.sendall(b'GET / HTTP/1.1\r\nHost: x\r\n\r\n')
data = b''
while True:
    c = s.recv(4096)
    if not c: break
    data += c
print('OK' if data.endswith(b'hello-from-origin') else 'BAD ' + repr(data))
"""

CLIENT_STRAY = r"""
import socket
s = socket.socket()
try:
    s.connect(('198.18.5.5', 443)); print('CONNECTED')
except OSError as e:
    print('ERR', e.errno)
"""

CLIENT_UDP = r"""
import socket, sys, errno
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
out = []
for name, fn in (('connect', lambda: s.connect(('8.8.8.8', 443))),
                 ('sendto', lambda: s.sendto(b'x', ('8.8.8.8', 443)))):
    try:
        fn(); out.append(name + '=LEAK')
    except OSError as e:
        out.append(name + '=blocked')
r = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)  # already pinned to 127.0.0.1 by the hook
s.sendto(b'ping', r.getsockname()); r.settimeout(2)
out.append('loopback=' + ('ok' if r.recv(10) == b'ping' else 'bad'))
print(' '.join(out))
"""

CLIENT_TFO = r"""
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
try:
    s.sendto(b'x', 0x20000000, ('8.8.8.8', 443))  # MSG_FASTOPEN
    print('LEAK')
except OSError as e:
    print('blocked', e.errno)
"""

failures = 0


def run(code, args, kind, port, auth=None, remote_dns=False):
    env = dict(os.environ, LD_PRELOAD=SHIM, BB_PROXY_TYPE=str(kind), BB_PROXY_PORT=str(port))
    if remote_dns:
        env["BB_PROXY_REMOTE_DNS"] = "1"
    if auth:
        env["BB_PROXY_USER"], env["BB_PROXY_PASS"] = auth
    r = subprocess.run([sys.executable, "-c", code, *args], env=env, capture_output=True, text=True, timeout=60)
    return (r.stdout + r.stderr).strip()


def check(name, got, want, record=None):
    global failures
    ok = got == want and (record is None or (seen and seen[-1] == record))
    if not ok:
        failures += 1
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {got}" + ("" if ok or record is None else f"  proxy saw {seen[-1:]} want {record}"))


for mode in ("blocking", "nonblocking"):
    check(f"socks5 ipv4 {mode}", run(CLIENT_TCP, ["93.184.216.34", "8080", mode], 1, SOCKS_PORT),
          "OK", ("socks5", "93.184.216.34", 8080, False))
    check(f"socks5 auth {mode}", run(CLIENT_TCP, ["93.184.216.34", "443", mode], 1, SOCKS_AUTH_PORT, (USER, PASS)),
          "OK", ("socks5", "93.184.216.34", 443, True))
    check(f"http connect {mode}", run(CLIENT_TCP, ["93.184.216.34", "443", mode], 2, HTTP_PORT, (USER, PASS)),
          "OK", ("http", "93.184.216.34", 443, True))

try:
    socket.socket(socket.AF_INET6, socket.SOCK_STREAM).close()
    have_v6 = True
except OSError:
    have_v6 = False
    print("SKIP  ipv6 cases: this host has no IPv6 support")
if have_v6:
    check("socks5 ipv6 destination", run(CLIENT_TCP, ["2606:2800:220:1::1", "443", "blocking"], 1, SOCKS_PORT),
          "OK", ("socks5", "2606:2800:220:1::1", 443, False))
    check("socks5 v4-mapped destination", run(CLIENT_TCP, ["::ffff:93.184.216.34", "443", "blocking"], 1, SOCKS_PORT),
          "OK", ("socks5", "93.184.216.34", 443, False))
    check("http ipv6 destination", run(CLIENT_TCP, ["2606:2800:220:1::1", "443", "blocking"], 2, HTTP_PORT, (USER, PASS)),
          "OK", ("http", "[2606:2800:220:1::1]", 443, True))

n = len(seen)
check("loopback bypasses proxy", run(CLIENT_TCP, ["127.0.0.1", str(ORIGIN_PORT), "blocking"], 1, SOCKS_PORT), "OK")
check("  (proxy not contacted)", str(len(seen) - n), "0")

check("socks5 wrong password fails closed", run(CLIENT_TCP, ["93.184.216.34", "443", "blocking"], 1, SOCKS_AUTH_PORT, (USER, "nope")), "ERR 111")
check("http wrong password fails closed", run(CLIENT_TCP, ["93.184.216.34", "443", "blocking"], 2, HTTP_PORT, (USER, "nope")), "ERR 111")
check("socks5 refused destination", run(CLIENT_TCP, ["93.184.216.34", "1", "blocking"], 1, SOCKS_PORT), "ERR 111")
dead = socket.socket(); dead.bind(("127.0.0.1", 0)); dead_port = dead.getsockname()[1]; dead.close()
check("proxy down fails closed", run(CLIENT_TCP, ["93.184.216.34", "443", "blocking"], 1, dead_port), "ERR 111")

NAME = "no-such-host.blackbox-test.invalid"
check("remote dns socks5 sends the name", run(CLIENT_DNS, [NAME, "443"], 1, SOCKS_PORT, remote_dns=True),
      "OK", ("socks5", NAME, 443, False))
check("remote dns http sends the name", run(CLIENT_DNS, [NAME, "443"], 2, HTTP_PORT, (USER, PASS), remote_dns=True),
      "OK", ("http", NAME, 443, True))
check("remote dns off resolves on the device", run(CLIENT_DNS, [NAME, "443"], 1, SOCKS_PORT), "GAIERR")
n = len(seen)
check("remote dns leaves localhost alone", run(CLIENT_TCP, ["localhost", str(ORIGIN_PORT), "blocking"], 1, SOCKS_PORT, remote_dns=True), "OK")
check("  (proxy not contacted)", str(len(seen) - n), "0")
check("remote dns numeric address unchanged", run(CLIENT_TCP, ["93.184.216.34", "8080", "blocking"], 1, SOCKS_PORT, remote_dns=True),
      "OK", ("socks5", "93.184.216.34", 8080, False))
check("unknown placeholder address is refused", run(CLIENT_STRAY, [], 1, SOCKS_PORT, remote_dns=True), "ERR 113")

check("udp", run(CLIENT_UDP, [], 1, SOCKS_PORT), "connect=blocked sendto=blocked loopback=ok")
check("tcp fast open", run(CLIENT_TFO, [], 1, SOCKS_PORT), "blocked 95")

print("\n%d failure(s)" % failures)
sys.exit(1 if failures else 0)
