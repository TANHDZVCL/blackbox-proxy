#include "ProxyHook.h"

#include <atomic>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

#include "BoxCore.h"
#include "Dobby/dobby.h"
#include "Log.h"
#include "Proxy/ProxyCore.h"
#include "xdl.h"

// How the hooks get in front of the kernel
// ----------------------------------------
// bionic routes connect() and socket() through a dispatch table owned by
// libnetd_client.so. That library exports netdClientInitConnect/Socket, which
// record the function to call *after* its own bookkeeping. Registering ours
// there puts the proxy logic right above the system call without patching any
// code. If that path is not live in this process, the raw system call stubs
// (__connect, __socket) are inline-hooked instead.

namespace {

    typedef int (*ConnectFn)(int, const sockaddr *, socklen_t);
    typedef int (*SocketFn)(int, int, int);
    typedef ssize_t (*SendtoFn)(int, const void *, size_t, int, const sockaddr *, socklen_t);
    typedef ssize_t (*SendmsgFn)(int, const msghdr *, unsigned int);
    typedef int (*SendmmsgFn)(int, mmsghdr *, unsigned int, int);

    int rawConnect(int fd, const sockaddr *addr, socklen_t len) {
        return (int) syscall(__NR_connect, fd, addr, len);
    }

    int rawSocket(int domain, int type, int protocol) {
        return (int) syscall(__NR_socket, domain, type, protocol);
    }

    ConnectFn gNextConnect = rawConnect;
    SocketFn gNextSocket = rawSocket;
    SendtoFn gOrigSendto = nullptr;
    SendmsgFn gOrigSendmsg = nullptr;
    SendmmsgFn gOrigSendmmsg = nullptr;

    std::atomic<bool> gConnectSeen{false};
    std::atomic<bool> gSocketSeen{false};

    int hookedConnect(int fd, const sockaddr *addr, socklen_t len) {
        gConnectSeen.store(true, std::memory_order_relaxed);
        return ProxyCore::onConnect(fd, addr, len, gNextConnect);
    }

    int hookedSocket(int domain, int type, int protocol) {
        gSocketSeen.store(true, std::memory_order_relaxed);
        return ProxyCore::onSocket(domain, type, protocol, gNextSocket);
    }

    ssize_t hookedSendto(int fd, const void *buf, size_t n, int flags, const sockaddr *dest,
                         socklen_t len) {
        int verdict = ProxyCore::sendVerdict(fd, dest, len);
        if (verdict != 0) {
            errno = verdict;
            return -1;
        }
        return gOrigSendto(fd, buf, n, flags, dest, len);
    }

    ssize_t hookedSendmsg(int fd, const msghdr *msg, unsigned int flags) {
        if (msg != nullptr) {
            int verdict = ProxyCore::sendVerdict(fd, (const sockaddr *) msg->msg_name,
                                                 msg->msg_namelen);
            if (verdict != 0) {
                errno = verdict;
                return -1;
            }
        }
        return gOrigSendmsg(fd, msg, flags);
    }

    int hookedSendmmsg(int fd, mmsghdr *msgs, unsigned int count, int flags) {
        for (unsigned int i = 0; msgs != nullptr && i < count; i++) {
            int verdict = ProxyCore::sendVerdict(fd, (const sockaddr *) msgs[i].msg_hdr.msg_name,
                                                 msgs[i].msg_hdr.msg_namelen);
            if (verdict != 0) {
                errno = verdict;
                return -1;
            }
        }
        return gOrigSendmmsg(fd, msgs, count, flags);
    }

    // Every resolver entry point in bionic (getaddrinfo, the Java resolver,
    // the NDK network API) funnels into one of these two, depending on the
    // Android version. Only the trailing arguments differ.
    typedef int (*ResolveContextFn)(const char *, const char *, const addrinfo *, const void *,
                                    addrinfo **);
    typedef int (*ResolveNetFn)(const char *, const char *, const addrinfo *, unsigned, unsigned,
                                addrinfo **);

    ResolveContextFn gOrigResolveContext = nullptr;
    ResolveNetFn gOrigResolveNet = nullptr;

    struct NetArgs {
        unsigned netId;
        unsigned mark;
    };

    int nextResolveContext(const char *node, const char *service, const addrinfo *hints,
                           addrinfo **res, void *context) {
        return gOrigResolveContext(node, service, hints, context, res);
    }

    int nextResolveNet(const char *node, const char *service, const addrinfo *hints, addrinfo **res,
                       void *context) {
        NetArgs *args = (NetArgs *) context;
        return gOrigResolveNet(node, service, hints, args->netId, args->mark, res);
    }

    int hookedResolveContext(const char *node, const char *service, const addrinfo *hints,
                             const void *netContext, addrinfo **res) {
        return ProxyCore::onResolve(node, service, hints, res, nextResolveContext,
                                    (void *) netContext);
    }

    int hookedResolveNet(const char *node, const char *service, const addrinfo *hints,
                         unsigned netId, unsigned mark, addrinfo **res) {
        NetArgs args{netId, mark};
        return ProxyCore::onResolve(node, service, hints, res, nextResolveNet, &args);
    }

    // Resolves a name that exists nowhere. It only succeeds, and only with a
    // placeholder address, when the resolver hook is live.
    bool resolveIsHooked() {
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo *res = nullptr;
        int saved = errno;
        bool ok = getaddrinfo("hook-probe.blackbox.invalid", nullptr, &hints, &res) == 0 &&
                  res != nullptr && ProxyCore::isPlaceholder(res->ai_addr);
        if (res != nullptr) freeaddrinfo(res);
        errno = saved;
        return ok;
    }

    // Both probes go through the public libc entry points, exactly like app
    // code does, and report whether our replacement was reached.
    bool connectIsHooked() {
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        gConnectSeen.store(false);
        int saved = errno;
        connect(-1, (const sockaddr *) &addr, sizeof(addr));
        errno = saved;
        return gConnectSeen.load();
    }

    bool socketIsHooked() {
        gSocketSeen.store(false);
        int saved = errno;
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) close(fd);
        errno = saved;
        return gSocketSeen.load();
    }

    void *findInLibc(void *libc, const char *name) {
        void *sym = xdl_sym(libc, name, nullptr);
        if (sym == nullptr) sym = xdl_dsym(libc, name, nullptr);
        return sym;
    }

    void registerWithNetdClient() {
        void *netd = xdl_open("libnetd_client.so", XDL_DEFAULT);
        if (netd == nullptr) {
            ALOGD("ProxyHook: libnetd_client.so is not loaded");
            return;
        }
        auto initConnect = (void (*)(ConnectFn *)) xdl_sym(netd, "netdClientInitConnect", nullptr);
        auto initSocket = (void (*)(SocketFn *)) xdl_sym(netd, "netdClientInitSocket", nullptr);
        if (initConnect != nullptr) {
            ConnectFn fn = hookedConnect;
            initConnect(&fn);
        }
        if (initSocket != nullptr) {
            SocketFn fn = hookedSocket;
            initSocket(&fn);
        }
        xdl_close(netd);
    }

    template<typename Fn>
    bool inlineHook(void *libc, const char *name, Fn replacement, Fn *orig) {
        void *target = findInLibc(libc, name);
        if (target == nullptr) return false;
        if (DobbyHook(target, (void *) replacement, (void **) orig) != 0) {
            *orig = nullptr;
            return false;
        }
        return true;
    }

    bool installResolveHook() {
        void *libc = xdl_open("libc.so", XDL_DEFAULT);
        if (libc == nullptr) return false;
        if (!inlineHook(libc, "android_getaddrinfofornetcontext", hookedResolveContext,
                        &gOrigResolveContext)) {
            inlineHook(libc, "android_getaddrinfofornet", hookedResolveNet, &gOrigResolveNet);
        }
        xdl_close(libc);
        bool ok = resolveIsHooked();
        ALOGD("ProxyHook: resolve=%d", ok);
        return ok;
    }

    int installResult = -1;

    int install() {
        if (installResult >= 0) return installResult;

        registerWithNetdClient();
        bool connectOk = connectIsHooked();
        bool socketOk = socketIsHooked();

        void *libc = xdl_open("libc.so", XDL_DEFAULT);
        if (libc == nullptr) {
            ALOGE("ProxyHook: cannot open libc.so");
            return ProxyHook::NO_CONNECT_HOOK | ProxyHook::NO_SOCKET_HOOK;
        }

        if (!connectOk) {
            ConnectFn orig = nullptr;
            if (inlineHook(libc, "__connect", hookedConnect, &orig)) gNextConnect = orig;
            connectOk = connectIsHooked();
        }
        if (!socketOk) {
            SocketFn orig = nullptr;
            if (inlineHook(libc, "__socket", hookedSocket, &orig)) gNextSocket = orig;
            socketOk = socketIsHooked();
        }

        // Explicit-destination sends. Since Android 10 the public functions
        // are thin dispatch wrappers, so the system call stubs are the target;
        // before that the public functions are the stubs.
        bool q = BoxCore::getApiLevel() >= 29;
        bool sendOk = inlineHook(libc, q ? "__sendto" : "sendto", hookedSendto, &gOrigSendto);
        sendOk &= inlineHook(libc, q ? "__sendmsg" : "sendmsg", hookedSendmsg, &gOrigSendmsg);
        sendOk &= inlineHook(libc, q ? "__sendmmsg" : "sendmmsg", hookedSendmmsg, &gOrigSendmmsg);
        xdl_close(libc);

        ALOGD("ProxyHook: connect=%d socket=%d send=%d", connectOk, socketOk, sendOk);
        if (!sendOk) {
            ALOGE("ProxyHook: send hooks unavailable, TCP Fast Open is not covered");
        }
        installResult = (connectOk ? 0 : ProxyHook::NO_CONNECT_HOOK) |
                        (socketOk ? 0 : ProxyHook::NO_SOCKET_HOOK) |
                        (sendOk ? 0 : ProxyHook::NO_SEND_HOOK);
        return installResult;
    }
}

int ProxyHook::enable(int type, const char *ip, int port, const char *user, const char *pass,
                      bool remoteDns) {
    if (!ProxyCore::configure(type, ip, port, user, pass)) {
        ALOGE("ProxyHook: invalid proxy configuration");
        return BAD_CONFIG;
    }
    int result = install();
    if (result & (NO_CONNECT_HOOK | NO_SOCKET_HOOK)) return result;
    if (remoteDns) {
        ProxyCore::setRemoteDns(true);
        if (!installResolveHook()) {
            ALOGE("ProxyHook: resolver hook unavailable, cannot resolve through the proxy");
            result |= NO_RESOLVE_HOOK;
        }
    }
    return result;
}
