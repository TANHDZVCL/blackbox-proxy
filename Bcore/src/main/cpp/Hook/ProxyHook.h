#ifndef BLACKBOX_PROXYHOOK_H
#define BLACKBOX_PROXYHOOK_H

namespace ProxyHook {
    // Result of enable(): OK, or a combination of the parts that failed.
    enum {
        OK = 0,
        BAD_CONFIG = 1,
        NO_CONNECT_HOOK = 2,
        NO_SOCKET_HOOK = 4,
        NO_RESOLVE_HOOK = 8,
        // Informational only, never returned alone as a failure: explicit
        // destination sends (TCP Fast Open) are not covered.
        NO_SEND_HOOK = 16,
    };

    // Routes every outbound connection of this process through the given
    // proxy. With `remoteDns`, host names are resolved by the proxy instead of
    // on the device. Unless the result is OK or just NO_SEND_HOOK, the proxy
    // is not enforced and the caller must refuse to run the app rather than
    // let it use the real network.
    int enable(int type, const char *ip, int port, const char *user, const char *pass,
               bool remoteDns);
}

#endif
