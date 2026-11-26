#ifndef BLACKBOX_PROXYCORE_H
#define BLACKBOX_PROXYCORE_H

#include <netdb.h>
#include <sys/socket.h>
#include <sys/types.h>

// Per-process outbound proxy logic. One virtual process belongs to exactly one
// profile (user), so the configuration is process-global.
//
// This file has no Android dependencies so it can be built and tested on a
// plain Linux host (see Proxy/test).
namespace ProxyCore {

    enum Type {
        NONE = 0,
        SOCKS5 = 1,
        HTTP = 2,
    };

    typedef int (*ConnectFn)(int, const struct sockaddr *, socklen_t);
    typedef int (*SocketFn)(int, int, int);

    typedef int (*ResolveFn)(const char *node, const char *service, const struct addrinfo *hints,
                             struct addrinfo **res, void *context);

    // `ip` must be a numeric IPv4 address. Returns false on invalid input, in
    // which case the proxy stays disabled.
    bool configure(int type, const char *ip, int port, const char *user, const char *pass);

    // When on, host names are not looked up on the device. onResolve() hands
    // out a placeholder address per name, and onConnect() asks the proxy to
    // connect to the name itself.
    void setRemoteDns(bool on);

    // Replacement for getaddrinfo(3). `next` is the real resolver; `context`
    // is passed through to it untouched.
    int onResolve(const char *node, const char *service, const struct addrinfo *hints,
                  struct addrinfo **res, ResolveFn next, void *context);

    // True when `addr` is a placeholder handed out by onResolve().
    bool isPlaceholder(const struct sockaddr *addr);

    bool enabled();

    // Replacement for connect(2). `next` performs the real connect.
    int onConnect(int fd, const struct sockaddr *addr, socklen_t len, ConnectFn next);

    // Replacement for socket(2). `next` creates the real socket. Datagram
    // sockets are pinned to loopback so nothing can leave the device outside
    // the proxy (QUIC, STUN/WebRTC, raw DNS).
    int onSocket(int domain, int type, int protocol, SocketFn next);

    // Verdict for a send that names an explicit destination: 0 to let it
    // through, otherwise the errno to fail with. Refuses datagrams to public
    // addresses and TCP Fast Open data, which would skip connect().
    int sendVerdict(int fd, const struct sockaddr *dest, socklen_t len);
}

#endif
