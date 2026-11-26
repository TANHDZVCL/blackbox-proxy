package top.niunaijun.blackbox.proxy;

import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Outbound proxy of one profile (virtual user). Every app of the profile sends
 * all of its TCP traffic through it; traffic the proxy cannot carry (UDP) is
 * blocked rather than sent from the device's own address.
 */
public class ProfileProxy {
    private static final String TAG = "ProfileProxy";

    public static final int TYPE_NONE = 0;
    public static final int TYPE_SOCKS5 = 1;
    public static final int TYPE_HTTP = 2;

    private static final int RESOLVE_TIMEOUT_MS = 8000;
    private static final int PROBE_TIMEOUT_MS = 15000;
    private static final String PROBE_HOST = "api.ipify.org";

    public final int type;
    public final String host;
    public final int port;
    public final String user;
    public final String pass;
    /** Resolve host names at the proxy rather than with the device's DNS. */
    public final boolean remoteDns;

    public ProfileProxy(int type, String host, int port, String user, String pass, boolean remoteDns) {
        this.remoteDns = remoteDns;
        this.type = type;
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
    }

    public boolean isValid() {
        return (type == TYPE_SOCKS5 || type == TYPE_HTTP) && !host.isEmpty() && port > 0
                && port <= 65535 && utf8(user).length <= 255 && utf8(pass).length <= 255;
    }

    /** Short description without credentials, for display. */
    public String summary() {
        return (type == TYPE_SOCKS5 ? "SOCKS5 " : "HTTP ") + host + ":" + port;
    }

    private static File fileFor(int userId) {
        return new File(new File(BEnvironment.getSystemDir(), "proxy"), userId + ".properties");
    }

    /** The proxy of a profile, or null when it connects directly. */
    public static ProfileProxy load(int userId) throws IOException {
        File file = fileFor(userId);
        if (!file.exists()) return null;
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        }
        int type;
        int port;
        try {
            type = Integer.parseInt(props.getProperty("type", "0"));
            port = Integer.parseInt(props.getProperty("port", "0"));
        } catch (NumberFormatException e) {
            throw new IOException("Corrupt proxy configuration for profile " + userId, e);
        }
        ProfileProxy proxy = new ProfileProxy(type, props.getProperty("host"), port,
                props.getProperty("user"), props.getProperty("pass"),
                Boolean.parseBoolean(props.getProperty("remoteDns", "false")));
        if (!proxy.isValid()) {
            throw new IOException("Invalid proxy configuration for profile " + userId);
        }
        return proxy;
    }

    /** Stores the proxy of a profile; null removes it. Applies to apps started afterwards. */
    public static void save(int userId, ProfileProxy proxy) throws IOException {
        File file = fileFor(userId);
        if (proxy == null) {
            if (file.exists() && !file.delete()) {
                throw new IOException("Cannot delete " + file);
            }
            return;
        }
        if (!proxy.isValid()) throw new IOException("Invalid proxy configuration");
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        Properties props = new Properties();
        props.setProperty("type", String.valueOf(proxy.type));
        props.setProperty("host", proxy.host);
        props.setProperty("port", String.valueOf(proxy.port));
        props.setProperty("user", proxy.user);
        props.setProperty("pass", proxy.pass);
        props.setProperty("remoteDns", String.valueOf(proxy.remoteDns));
        // Write-then-rename so a starting app never reads a half-written file.
        File tmp = new File(dir, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            props.store(out, null);
            out.getFD().sync();
        }
        if (!tmp.renameTo(file)) throw new IOException("Cannot write " + file);
    }

    /**
     * Called in a virtual app process before any app code runs. If the profile
     * has a proxy that cannot be enforced, the process is killed: an app must
     * never fall back to the device's real address unnoticed.
     */
    public static void enforceForCurrentProcess(int userId) {
        String failure;
        try {
            ProfileProxy proxy = load(userId);
            if (proxy == null) return;
            String ip = proxy.resolveIpv4();
            if (NativeCore.enableProxy(proxy.type, ip, proxy.port, proxy.user, proxy.pass, proxy.remoteDns)) {
                Slog.d(TAG, "Profile " + userId + " uses " + proxy.summary());
                return;
            }
            failure = "network hooks could not be installed";
        } catch (Throwable e) {
            failure = String.valueOf(e);
        }
        Slog.e(TAG, "Refusing to start: proxy of profile " + userId + " cannot be enforced: " + failure);
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private String resolveIpv4() throws IOException {
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return host;
        // Off the main thread: this runs while the app is being bound.
        final AtomicReference<Object> result = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address instanceof Inet4Address) {
                        result.set(address.getHostAddress());
                        return;
                    }
                }
                result.set(new IOException("No IPv4 address for proxy host " + host));
            } catch (Throwable e) {
                result.set(e);
            }
        }, "ProxyResolve");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(RESOLVE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Object value = result.get();
        if (value instanceof String) return (String) value;
        if (value instanceof Throwable) throw new IOException("Cannot resolve proxy host " + host, (Throwable) value);
        throw new IOException("Timed out resolving proxy host " + host);
    }

    /**
     * Connects through this proxy and returns the public IP address the
     * outside world sees. Blocking; call off the main thread. Runs in the host
     * process, so it speaks the proxy protocol itself.
     */
    public String probeExitIp() throws IOException {
        if (!isValid()) throw new IOException("Invalid proxy configuration");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_TIMEOUT_MS);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            if (type == TYPE_SOCKS5) {
                socks5Connect(in, out, PROBE_HOST, 80);
            } else {
                httpConnect(in, out, PROBE_HOST, 80);
            }
            out.write(utf8("GET / HTTP/1.1\r\nHost: " + PROBE_HOST + "\r\nConnection: close\r\n\r\n"));
            out.flush();
            String response = new String(readToEnd(in), StandardCharsets.ISO_8859_1);
            int bodyStart = response.indexOf("\r\n\r\n");
            if (!response.startsWith("HTTP/") || !response.substring(8, 12).equals(" 200") || bodyStart < 0) {
                throw new IOException("Unexpected reply from " + PROBE_HOST);
            }
            String body = response.substring(bodyStart + 4).trim();
            if (!body.matches("[0-9a-fA-F.:]{3,45}")) {
                throw new IOException("Unexpected reply from " + PROBE_HOST);
            }
            return body;
        }
    }

    private void socks5Connect(InputStream in, OutputStream out, String targetHost, int targetPort) throws IOException {
        boolean auth = !user.isEmpty();
        out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        byte[] reply = readExact(in, 2);
        if (reply[0] != 5) throw new IOException("Not a SOCKS5 proxy");
        if (reply[1] == 2) {
            if (!auth) throw new IOException("Proxy requires a username and password");
            byte[] u = utf8(user), p = utf8(pass);
            byte[] msg = new byte[3 + u.length + p.length];
            msg[0] = 1;
            msg[1] = (byte) u.length;
            System.arraycopy(u, 0, msg, 2, u.length);
            msg[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, msg, 3 + u.length, p.length);
            out.write(msg);
            if (readExact(in, 2)[1] != 0) throw new IOException("Proxy rejected the username or password");
        } else if (reply[1] != 0) {
            throw new IOException("Proxy offers no usable authentication method");
        }
        byte[] name = utf8(targetHost);
        byte[] request = new byte[7 + name.length];
        request[0] = 5;
        request[1] = 1;
        request[3] = 3;
        request[4] = (byte) name.length;
        System.arraycopy(name, 0, request, 5, name.length);
        request[5 + name.length] = (byte) (targetPort >> 8);
        request[6 + name.length] = (byte) targetPort;
        out.write(request);
        byte[] head = readExact(in, 4);
        if (head[1] != 0) throw new IOException("Proxy could not connect (SOCKS5 error " + head[1] + ")");
        int skip = head[3] == 1 ? 4 : head[3] == 4 ? 16 : (readExact(in, 1)[0] & 0xff);
        readExact(in, skip + 2);
    }

    private void httpConnect(InputStream in, OutputStream out, String targetHost, int targetPort) throws IOException {
        StringBuilder request = new StringBuilder();
        request.append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n");
        if (!user.isEmpty()) {
            String token = android.util.Base64.encodeToString(utf8(user + ":" + pass), android.util.Base64.NO_WRAP);
            request.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
        }
        request.append("\r\n");
        out.write(utf8(request.toString()));
        out.flush();
        StringBuilder head = new StringBuilder();
        while (head.length() < 8192 && head.indexOf("\r\n\r\n") < 0) {
            head.append((char) (readExact(in, 1)[0] & 0xff));
        }
        if (head.length() < 12 || !head.substring(8, 12).equals(" 200")) {
            int eol = head.indexOf("\r\n");
            throw new IOException("Proxy refused: " + (eol > 0 ? head.substring(0, eol) : "no reply"));
        }
    }

    private static byte[] readExact(InputStream in, int count) throws IOException {
        byte[] data = new byte[count];
        int done = 0;
        while (done < count) {
            int n = in.read(data, done, count - done);
            if (n < 0) throw new IOException("Proxy closed the connection");
            done += n;
        }
        return data;
    }

    private static byte[] readToEnd(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while (buffer.size() < 65536 && (n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
