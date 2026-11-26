#include "ProxyCore.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include <string>

namespace {

    const int kTimeoutMs = 20000;

    struct Config {
        int type = ProxyCore::NONE;
        in_addr ip{};
        uint16_t port = 0; // host order
        std::string user;
        std::string pass;
    };

    // Written once before the hooks are installed, read-only afterwards.
    // Function-local so it is usable no matter how early configure() runs.
    Config &config() {
        static Config instance;
        return instance;
    }

    int64_t nowMs() {
        timespec ts{};
        clock_gettime(CLOCK_MONOTONIC, &ts);
        return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    }

    bool isLocalV4(uint32_t hostOrder) {
        uint8_t a = hostOrder >> 24, b = (hostOrder >> 16) & 0xff;
        if (a == 0 || a == 10 || a == 127) return true;
        if (a == 169 && b == 254) return true;
        if (a == 172 && b >= 16 && b <= 31) return true;
        if (a == 192 && b == 168) return true;
        if (a == 100 && b >= 64 && b <= 127) return true; // CGNAT
        if (a >= 224) return true; // multicast, reserved, broadcast
        return false;
    }

    bool mappedV4(const in6_addr &a, uint32_t *v4HostOrder) {
        if (!IN6_IS_ADDR_V4MAPPED(&a)) return false;
        uint32_t n;
        memcpy(&n, &a.s6_addr[12], 4);
        *v4HostOrder = ntohl(n);
        return true;
    }

    // Destinations that never go through the proxy: this device and the LAN.
    bool isLocal(const sockaddr *addr) {
        if (addr->sa_family == AF_INET) {
            return isLocalV4(ntohl(((const sockaddr_in *) addr)->sin_addr.s_addr));
        }
        if (addr->sa_family == AF_INET6) {
            const in6_addr &a = ((const sockaddr_in6 *) addr)->sin6_addr;
            uint32_t v4;
            if (mappedV4(a, &v4)) return isLocalV4(v4);
            if (IN6_IS_ADDR_UNSPECIFIED(&a) || IN6_IS_ADDR_LOOPBACK(&a)) return true;
            if (IN6_IS_ADDR_LINKLOCAL(&a) || IN6_IS_ADDR_MULTICAST(&a)) return true;
            if ((a.s6_addr[0] & 0xfe) == 0xfc) return true; // unique local
            return false;
        }
        return true;
    }

    bool isProxyItself(const sockaddr *addr) {
        uint32_t ip;
        uint16_t port;
        if (addr->sa_family == AF_INET) {
            ip = ((const sockaddr_in *) addr)->sin_addr.s_addr;
            port = ntohs(((const sockaddr_in *) addr)->sin_port);
        } else {
            uint32_t host;
            if (!mappedV4(((const sockaddr_in6 *) addr)->sin6_addr, &host)) return false;
            ip = htonl(host);
            port = ntohs(((const sockaddr_in6 *) addr)->sin6_port);
        }
        return ip == config().ip.s_addr && port == config().port;
    }

    int socketType(int fd) {
        int type = 0;
        socklen_t len = sizeof(type);
        if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0) return -1;
        return type;
    }

    bool waitFor(int fd, short events, int64_t deadline) {
        for (;;) {
            int64_t left = deadline - nowMs();
            if (left <= 0) {
                errno = ETIMEDOUT;
                return false;
            }
            pollfd p{fd, events, 0};
            int r = poll(&p, 1, (int) left);
            if (r > 0) return true;
            if (r == 0) {
                errno = ETIMEDOUT;
                return false;
            }
            if (errno != EINTR) return false;
        }
    }

    // The handshake talks to the kernel directly so it never re-enters the
    // hooked libc entry points and never raises SIGPIPE.
    bool sendAll(int fd, const void *buf, size_t len, int64_t deadline) {
        const char *p = (const char *) buf;
        while (len > 0) {
            ssize_t n = syscall(SYS_sendto, fd, p, len, MSG_NOSIGNAL, nullptr, 0);
            if (n > 0) {
                p += n;
                len -= (size_t) n;
            } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                if (!waitFor(fd, POLLOUT, deadline)) return false;
            } else if (n < 0 && errno == EINTR) {
                continue;
            } else {
                return false;
            }
        }
        return true;
    }

    bool recvAll(int fd, void *buf, size_t len, int64_t deadline) {
        char *p = (char *) buf;
        while (len > 0) {
            ssize_t n = recv(fd, p, len, 0);
            if (n > 0) {
                p += n;
                len -= (size_t) n;
            } else if (n == 0) {
                errno = ECONNRESET;
                return false;
            } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (!waitFor(fd, POLLIN, deadline)) return false;
            } else if (errno != EINTR) {
                return false;
            }
        }
        return true;
    }

    struct Target {
        bool v6;
        uint8_t addr[16]; // 4 bytes used for IPv4
        uint16_t port;    // host order
    };

    Target targetOf(const sockaddr *addr) {
        Target t{};
        if (addr->sa_family == AF_INET) {
            const sockaddr_in *in = (const sockaddr_in *) addr;
            memcpy(t.addr, &in->sin_addr, 4);
            t.port = ntohs(in->sin_port);
        } else {
            const sockaddr_in6 *in6 = (const sockaddr_in6 *) addr;
            uint32_t v4;
            if (mappedV4(in6->sin6_addr, &v4)) {
                memcpy(t.addr, &in6->sin6_addr.s6_addr[12], 4);
            } else {
                t.v6 = true;
                memcpy(t.addr, &in6->sin6_addr, 16);
            }
            t.port = ntohs(in6->sin6_port);
        }
        return t;
    }

    bool socks5Handshake(int fd, const Target &t, int64_t deadline) {
        bool auth = !config().user.empty();
        uint8_t hello[4] = {5, (uint8_t) (auth ? 2 : 1), 0, 2};
        if (!sendAll(fd, hello, auth ? 4 : 3, deadline)) return false;
        uint8_t reply[2];
        if (!recvAll(fd, reply, 2, deadline)) return false;
        if (reply[0] != 5) return false;
        if (reply[1] == 2) {
            if (!auth) return false;
            std::string msg;
            msg.push_back(1);
            msg.push_back((char) config().user.size());
            msg += config().user;
            msg.push_back((char) config().pass.size());
            msg += config().pass;
            if (!sendAll(fd, msg.data(), msg.size(), deadline)) return false;
            if (!recvAll(fd, reply, 2, deadline)) return false;
            if (reply[1] != 0) return false;
        } else if (reply[1] != 0) {
            return false;
        }

        uint8_t req[4 + 16 + 2] = {5, 1, 0, (uint8_t) (t.v6 ? 4 : 1)};
        size_t n = 4;
        size_t alen = t.v6 ? 16 : 4;
        memcpy(req + n, t.addr, alen);
        n += alen;
        req[n++] = (uint8_t) (t.port >> 8);
        req[n++] = (uint8_t) (t.port & 0xff);
        if (!sendAll(fd, req, n, deadline)) return false;

        uint8_t head[4];
        if (!recvAll(fd, head, 4, deadline)) return false;
        if (head[0] != 5 || head[1] != 0) return false;
        size_t skip;
        if (head[3] == 1) {
            skip = 4;
        } else if (head[3] == 4) {
            skip = 16;
        } else if (head[3] == 3) {
            uint8_t l;
            if (!recvAll(fd, &l, 1, deadline)) return false;
            skip = l;
        } else {
            return false;
        }
        uint8_t bound[255 + 2];
        return recvAll(fd, bound, skip + 2, deadline);
    }

    std::string base64(const std::string &in) {
        static const char tbl[] =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        std::string out;
        size_t i = 0;
        for (; i + 2 < in.size(); i += 3) {
            uint32_t v = ((uint8_t) in[i] << 16) | ((uint8_t) in[i + 1] << 8) | (uint8_t) in[i + 2];
            out += tbl[v >> 18];
            out += tbl[(v >> 12) & 63];
            out += tbl[(v >> 6) & 63];
            out += tbl[v & 63];
        }
        if (i + 1 == in.size()) {
            uint32_t v = (uint8_t) in[i] << 16;
            out += tbl[v >> 18];
            out += tbl[(v >> 12) & 63];
            out += "==";
        } else if (i + 2 == in.size()) {
            uint32_t v = ((uint8_t) in[i] << 16) | ((uint8_t) in[i + 1] << 8);
            out += tbl[v >> 18];
            out += tbl[(v >> 12) & 63];
            out += tbl[(v >> 6) & 63];
            out += '=';
        }
        return out;
    }

    bool httpHandshake(int fd, const Target &t, int64_t deadline) {
        char ip[INET6_ADDRSTRLEN];
        inet_ntop(t.v6 ? AF_INET6 : AF_INET, t.addr, ip, sizeof(ip));
        char hostPort[INET6_ADDRSTRLEN + 16];
        snprintf(hostPort, sizeof(hostPort), t.v6 ? "[%s]:%u" : "%s:%u", ip, (unsigned) t.port);

        std::string req = std::string("CONNECT ") + hostPort + " HTTP/1.1\r\nHost: " + hostPort +
                          "\r\nProxy-Connection: keep-alive\r\n";
        if (!config().user.empty()) {
            req += "Proxy-Authorization: Basic " + base64(config().user + ":" + config().pass) +
                   "\r\n";
        }
        req += "\r\n";
        if (!sendAll(fd, req.data(), req.size(), deadline)) return false;

        // Read one byte at a time: anything after the blank line already
        // belongs to the tunnelled connection.
        std::string resp;
        while (resp.size() < 8192) {
            char c;
            if (!recvAll(fd, &c, 1, deadline)) return false;
            resp.push_back(c);
            if (resp.size() >= 4 && resp.compare(resp.size() - 4, 4, "\r\n\r\n") == 0) {
                return resp.compare(0, 5, "HTTP/") == 0 && resp.size() >= 12 &&
                       resp.compare(8, 4, " 200") == 0;
            }
        }
        return false;
    }

    int connectViaProxy(int fd, const sockaddr *addr, ProxyCore::ConnectFn next) {
        Target target = targetOf(addr);

        sockaddr_storage proxy{};
        socklen_t proxyLen;
        if (addr->sa_family == AF_INET) {
            sockaddr_in *in = (sockaddr_in *) &proxy;
            in->sin_family = AF_INET;
            in->sin_addr = config().ip;
            in->sin_port = htons(config().port);
            proxyLen = sizeof(sockaddr_in);
        } else {
            // IPv6 socket: reach the IPv4 proxy through a v4-mapped address.
            sockaddr_in6 *in6 = (sockaddr_in6 *) &proxy;
            in6->sin6_family = AF_INET6;
            in6->sin6_addr.s6_addr[10] = 0xff;
            in6->sin6_addr.s6_addr[11] = 0xff;
            memcpy(&in6->sin6_addr.s6_addr[12], &config().ip, 4);
            in6->sin6_port = htons(config().port);
            proxyLen = sizeof(sockaddr_in6);
        }

        int savedErrno = errno;
        int flags = fcntl(fd, F_GETFL);
        if (flags < 0) return -1;
        bool wasBlocking = !(flags & O_NONBLOCK);
        if (wasBlocking) fcntl(fd, F_SETFL, flags | O_NONBLOCK);

        int64_t deadline = nowMs() + kTimeoutMs;
        int err = 0;
        int r;
        do {
            r = next(fd, (const sockaddr *) &proxy, proxyLen);
        } while (r != 0 && errno == EINTR);

        if (r != 0) {
            if (errno == EINPROGRESS) {
                if (!waitFor(fd, POLLOUT, deadline)) {
                    err = errno;
                } else {
                    socklen_t l = sizeof(err);
                    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &l) != 0) err = errno;
                }
            } else {
                // EISCONN, EALREADY, EBADF...: exactly what the caller would
                // have seen from a plain connect().
                err = errno;
                if (wasBlocking) fcntl(fd, F_SETFL, flags);
                errno = err;
                return -1;
            }
        }

        if (err == 0) {
            bool ok = config().type == ProxyCore::SOCKS5 ? socks5Handshake(fd, target, deadline)
                                                        : httpHandshake(fd, target, deadline);
            if (!ok) err = errno == ETIMEDOUT ? ETIMEDOUT : ECONNREFUSED;
        }

        if (wasBlocking) fcntl(fd, F_SETFL, flags);
        if (err != 0) {
            errno = err;
            return -1;
        }
        errno = savedErrno;
        return 0;
    }
}

bool ProxyCore::configure(int type, const char *ip, int port, const char *user, const char *pass) {
    Config c;
    if (type != SOCKS5 && type != HTTP) return false;
    if (ip == nullptr || inet_pton(AF_INET, ip, &c.ip) != 1) return false;
    if (port <= 0 || port > 65535) return false;
    c.type = type;
    c.port = (uint16_t) port;
    if (user) c.user = user;
    if (pass) c.pass = pass;
    if (c.user.size() > 255 || c.pass.size() > 255) return false;
    config() = c;
    return true;
}

bool ProxyCore::enabled() {
    return config().type != NONE;
}

int ProxyCore::onConnect(int fd, const sockaddr *addr, socklen_t len, ConnectFn next) {
    if (!enabled() || addr == nullptr) return next(fd, addr, len);
    if (addr->sa_family == AF_INET) {
        if (len < (socklen_t) sizeof(sockaddr_in)) return next(fd, addr, len);
    } else if (addr->sa_family == AF_INET6) {
        if (len < (socklen_t) sizeof(sockaddr_in6)) return next(fd, addr, len);
    } else {
        return next(fd, addr, len); // unix sockets, AF_UNSPEC disconnect...
    }
    if (isLocal(addr) || isProxyItself(addr)) return next(fd, addr, len);

    int type = socketType(fd);
    if (type < 0) return next(fd, addr, len); // not a socket: let the kernel report it
    if (type != SOCK_STREAM) {
        errno = ENETUNREACH;
        return -1;
    }
    return connectViaProxy(fd, addr, next);
}

int ProxyCore::onSocket(int domain, int type, int protocol, SocketFn next) {
    int fd = next(domain, type, protocol);
    if (fd < 0 || !enabled()) return fd;
    if (domain != AF_INET && domain != AF_INET6) return fd;
    int base = type & ~(SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (base == SOCK_STREAM) return fd;

    // Datagram (and anything else that is not TCP): the proxy cannot carry it,
    // so pin it to loopback. Sends to public addresses then fail in the kernel.
    int savedErrno = errno;
    int r;
    if (domain == AF_INET) {
        sockaddr_in in{};
        in.sin_family = AF_INET;
        in.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        r = bind(fd, (const sockaddr *) &in, sizeof(in));
    } else {
        sockaddr_in6 in6{};
        in6.sin6_family = AF_INET6;
        in6.sin6_addr = in6addr_loopback;
        r = bind(fd, (const sockaddr *) &in6, sizeof(in6));
    }
    if (r != 0) {
        // Never hand out a socket that could bypass the proxy.
        close(fd);
        errno = EACCES;
        return -1;
    }
    errno = savedErrno;
    return fd;
}

int ProxyCore::sendVerdict(int fd, const sockaddr *dest, socklen_t len) {
    if (!enabled() || dest == nullptr) return 0;
    if (dest->sa_family == AF_INET) {
        if (len < (socklen_t) sizeof(sockaddr_in)) return 0;
    } else if (dest->sa_family == AF_INET6) {
        if (len < (socklen_t) sizeof(sockaddr_in6)) return 0;
    } else {
        return 0;
    }
    if (isLocal(dest)) return 0;
    if (socketType(fd) == SOCK_STREAM) {
        // The kernel ignores the destination on a connected TCP socket. On an
        // unconnected one this is TCP Fast Open, which would skip connect().
        sockaddr_storage peer{};
        socklen_t peerLen = sizeof(peer);
        if (getpeername(fd, (sockaddr *) &peer, &peerLen) == 0) return 0;
        return EOPNOTSUPP;
    }
    return ENETUNREACH;
}
