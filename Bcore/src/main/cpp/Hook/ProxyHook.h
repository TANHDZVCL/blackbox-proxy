#ifndef BLACKBOX_PROXYHOOK_H
#define BLACKBOX_PROXYHOOK_H

namespace ProxyHook {
    // Routes every outbound connection of this process through the given
    // proxy. Returns false when the proxy could not be enforced; the caller
    // must then refuse to run the app rather than let it use the real network.
    // With `remoteDns`, host names are resolved by the proxy instead of on the
    // device.
    bool enable(int type, const char *ip, int port, const char *user, const char *pass,
                bool remoteDns);
}

#endif
