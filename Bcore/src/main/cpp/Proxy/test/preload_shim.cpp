// Host-side harness: exposes ProxyCore through LD_PRELOAD so ordinary Linux
// programs exercise the same code path the Android hooks use.
#include <errno.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "../ProxyCore.h"

static int rawConnect(int fd, const sockaddr *addr, socklen_t len) {
    return (int) syscall(SYS_connect, fd, addr, len);
}

static int rawSocket(int domain, int type, int protocol) {
    return (int) syscall(SYS_socket, domain, type, protocol);
}

__attribute__((constructor)) static void setup() {
    const char *type = getenv("BB_PROXY_TYPE");
    const char *port = getenv("BB_PROXY_PORT");
    if (type == nullptr || port == nullptr) return;
    ProxyCore::configure(atoi(type), "127.0.0.1", atoi(port), getenv("BB_PROXY_USER"),
                         getenv("BB_PROXY_PASS"));
}

extern "C" {

int connect(int fd, const sockaddr *addr, socklen_t len) {
    return ProxyCore::onConnect(fd, addr, len, rawConnect);
}

int socket(int domain, int type, int protocol) {
    return ProxyCore::onSocket(domain, type, protocol, rawSocket);
}

ssize_t sendto(int fd, const void *buf, size_t n, int flags, const sockaddr *dest, socklen_t len) {
    int verdict = ProxyCore::sendVerdict(fd, dest, len);
    if (verdict != 0) {
        errno = verdict;
        return -1;
    }
    return syscall(SYS_sendto, fd, buf, n, flags, dest, len);
}

ssize_t sendmsg(int fd, const msghdr *msg, int flags) {
    if (msg != nullptr) {
        int verdict = ProxyCore::sendVerdict(fd, (const sockaddr *) msg->msg_name, msg->msg_namelen);
        if (verdict != 0) {
            errno = verdict;
            return -1;
        }
    }
    return syscall(SYS_sendmsg, fd, msg, flags);
}

}
