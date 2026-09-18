package link.e4all;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NetDns {
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    // plain jdk sockets, the shaded netty resolver crashed on old mc versions
    private static final int DNS_PORT = 53;
    private static final int QUERY_TIMEOUT_MS = 5000;
    private static final int UDP_BUFFER_BYTES = 4096;
    private static final int MAX_CNAME_DEPTH = 5;
    private static final long CACHE_MIN_TTL_MS = 30_000L;
    private static final long CACHE_MAX_TTL_MS = 300_000L;
    private static final int MAX_POINTER_JUMPS = 16;

    private static final int TYPE_A = 1;
    private static final int TYPE_CNAME = 5;
    private static final int TYPE_AAAA = 28;
    private static final int CLASS_IN = 1;
    private static final int RCODE_NXDOMAIN = 3;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, CachedAddresses> CACHE = new ConcurrentHashMap<>();

    private NetDns() {}

    private static final class CachedAddresses {
        final List<InetAddress> addresses;
        final long expiresAt;

        CachedAddresses(List<InetAddress> addresses, long ttlMillis) {
            this.addresses = addresses;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
        }

        boolean valid() {
            return System.currentTimeMillis() < expiresAt;
        }
    }

    private static final class LookupResult {
        final List<InetAddress> addresses;
        final long ttlMillis;

        LookupResult(List<InetAddress> addresses, long ttlMillis) {
            this.addresses = addresses;
            this.ttlMillis = ttlMillis;
        }
    }

    private static final class ParsedAnswers {
        final List<InetAddress> addresses = new ArrayList<>();
        String cname;
        long ttlMillis = CACHE_MIN_TTL_MS;
    }

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // no /etc/resolv.conf on android, so dns is done here
    public static InetAddress resolve(String host) throws UnknownHostException {
        if (host == null || host.isEmpty()) {
            throw new UnknownHostException("empty host");
        }
        if (isIpLiteral(host)) {
            return InetAddress.getByName(host);
        }
        String key = host.toLowerCase(Locale.ROOT);
        CachedAddresses cached = CACHE.get(key);
        if (cached != null && cached.valid()) {
            return cached.addresses.get(0);
        }
        try {
            LookupResult result = lookup(host);
            if (!result.addresses.isEmpty()) {
                CACHE.put(key, new CachedAddresses(result.addresses, result.ttlMillis));
                return result.addresses.get(0);
            }
            LOGGER.warn("e4all: dns returned no address for {}, trying system resolver", host);
        } catch (Throwable t) {
            // catch Throwable, link errors used to escape and kill the session here
            LOGGER.warn("e4all: dns lookup for {} failed ({}), trying system resolver", host, t.toString());
            LOGGER.debug("e4all: dns lookup failure detail", t);
        }
        return resolveWithSystem(host, key);
    }

    private static InetAddress resolveWithSystem(String host, String cacheKey) throws UnknownHostException {
        InetAddress[] system = InetAddress.getAllByName(host);
        List<InetAddress> addresses = new ArrayList<>(system.length);
        for (InetAddress addr : system) {
            if (addr instanceof Inet4Address) addresses.add(addr);
        }
        for (InetAddress addr : system) {
            if (!(addr instanceof Inet4Address)) addresses.add(addr);
        }
        if (addresses.isEmpty()) {
            throw new UnknownHostException(host);
        }
        CACHE.put(cacheKey, new CachedAddresses(addresses, CACHE_MIN_TTL_MS));
        return addresses.get(0);
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    // raw sockets, netty's http codec is missing on older mc versions
    public static Response httpGet(URI uri) throws Exception {
        String host = uri.getHost();
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int defaultPort = tls ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
        InetAddress addr = resolve(host);

        Socket socket = tls
                ? (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket()
                : new Socket();
        try {
            socket.connect(new InetSocketAddress(addr, port), 10000);
            socket.setSoTimeout(20000);
            if (tls) {
                SSLSocket ssl = (SSLSocket) socket;
                SSLParameters params = ssl.getSSLParameters();
                params.setServerNames(List.of(new SNIHostName(host)));
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(params);
            }
            return sendRequest(socket, uri, host, port, defaultPort);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static Response sendRequest(Socket socket, URI uri, String host, int port, int defaultPort) throws IOException {
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String hostHeader = host + (port == defaultPort ? "" : ":" + port);

        OutputStream out = socket.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Accept: application/json\r\n"
                + "Connection: close\r\n"
                + "User-Agent: e4all\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        String statusLine = readAsciiLine(in);
        if (statusLine == null || !statusLine.startsWith("HTTP/")) {
            throw new IOException("malformed http response: " + statusLine);
        }
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2) {
            throw new IOException("malformed status line: " + statusLine);
        }
        int status = Integer.parseInt(statusParts[1]);

        Map<String, String> headers = new HashMap<>();
        String h;
        while ((h = readAsciiLine(in)) != null && !h.isEmpty()) {
            int sep = h.indexOf(':');
            if (sep > 0) {
                headers.put(h.substring(0, sep).trim().toLowerCase(Locale.ROOT),
                        h.substring(sep + 1).trim());
            }
        }

        byte[] body = readBody(in, headers);
        return new Response(status, new String(body, StandardCharsets.UTF_8));
    }

    // Connection: close is always requested, but some servers still send content-length or chunked
    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String h;
            while (true) {
                String sizeLine = readAsciiLine(in);
                if (sizeLine == null) break;
                int size = Integer.parseInt(sizeLine.split(";", 2)[0].trim(), 16);
                if (size == 0) {
                    while ((h = readAsciiLine(in)) != null && !h.isEmpty()) {}
                    break;
                }
                byte[] chunk = new byte[size];
                int read = 0;
                while (read < size) {
                    int r = in.read(chunk, read, size - read);
                    if (r == -1) throw new IOException("truncated chunked body");
                    read += r;
                }
                body.write(chunk, 0, size);
                if (body.size() > MAX_BODY_BYTES) throw new IOException("response body too large");
                readAsciiLine(in);
            }
            return body.toByteArray();
        }
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            return readFully(in, Integer.parseInt(contentLength.trim()));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
            body.write(buf, 0, r);
            if (body.size() > MAX_BODY_BYTES) throw new IOException("response body too large");
        }
        return body.toByteArray();
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        if (len > MAX_BODY_BYTES) throw new IOException("response body too large");
        byte[] out = new byte[len];
        int read = 0;
        while (read < len) {
            int r = in.read(out, read, len - read);
            if (r == -1) throw new IOException("truncated response body");
            read += r;
        }
        return out;
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(80);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("US-ASCII");
    }

    private static LookupResult lookup(String host) throws IOException {
        LookupResult ipv4 = lookupType(host, TYPE_A, 0);
        if (!ipv4.addresses.isEmpty()) {
            return ipv4;
        }
        LookupResult ipv6 = lookupType(host, TYPE_AAAA, 0);
        return ipv6.addresses.isEmpty() ? ipv4 : ipv6;
    }

    private static LookupResult lookupType(String host, int type, int depth) throws IOException {
        ParsedAnswers parsed = parseAnswers(query(host, type), type);
        if (!parsed.addresses.isEmpty() || parsed.cname == null || depth >= MAX_CNAME_DEPTH) {
            return new LookupResult(parsed.addresses, parsed.ttlMillis);
        }
        LOGGER.debug("e4all: dns cname {} -> {}", host, parsed.cname);
        return lookupType(parsed.cname, type, depth + 1);
    }

    private static byte[] query(String host, int type) throws IOException {
        int id = RANDOM.nextInt(0x10000);
        byte[] request = buildQuery(id, host, type);
        List<InetSocketAddress> servers = nameServers();
        if (servers.isEmpty()) {
            throw new IOException("no nameservers configured");
        }
        IOException last = null;
        for (InetSocketAddress server : servers) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    byte[] response = sendUdp(server, request, id);
                    if (response == null) {
                        last = new IOException("no dns response from " + server);
                        continue;
                    }
                    if (isTruncated(response)) {
                        byte[] overTcp = sendTcp(server, request, id);
                        if (overTcp != null) {
                            return overTcp;
                        }
                        LOGGER.debug("e4all: tcp dns retry via {} failed, using truncated udp answer", server);
                    }
                    return response;
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new IOException("dns query for " + host + " failed");
    }

    private static byte[] sendUdp(InetSocketAddress server, byte[] request, int id) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(QUERY_TIMEOUT_MS);
            socket.send(new DatagramPacket(request, request.length, server));
            byte[] buffer = new byte[UDP_BUFFER_BYTES];
            long deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (java.net.SocketTimeoutException e) {
                    return null;
                }
                if (response.getLength() < 12 || readShort(buffer, 0) != id) {
                    continue; // wrong packet, keep waiting
                }
                byte[] copy = new byte[response.getLength()];
                System.arraycopy(buffer, 0, copy, 0, response.getLength());
                return copy;
            }
            return null;
        }
    }

    private static byte[] sendTcp(InetSocketAddress server, byte[] request, int id) {
        try (Socket socket = new Socket()) {
            socket.connect(server, QUERY_TIMEOUT_MS);
            socket.setSoTimeout(QUERY_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((request.length >> 8) & 0xFF);
            out.write(request.length & 0xFF);
            out.write(request);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = in.readUnsignedShort();
            byte[] response = new byte[length];
            in.readFully(response);
            if (response.length < 12 || readShort(response, 0) != id) {
                return null;
            }
            return response;
        } catch (IOException e) {
            LOGGER.debug("e4all: tcp dns retry failed", e);
            return null;
        }
    }

    private static byte[] buildQuery(int id, String host, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        writeShort(out, id);
        writeShort(out, 0x0100);
        writeShort(out, 1);     
        writeShort(out, 0);     
        writeShort(out, 0);     
        writeShort(out, 0);     
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        for (String label : name.split("\\.", -1)) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IOException("invalid dns label in " + host);
            }
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        writeShort(out, type);
        writeShort(out, CLASS_IN);
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static boolean isTruncated(byte[] response) {
        return response.length >= 4 && (response[2] & 0x02) != 0;
    }

    private static int skipName(byte[] data, int offset) throws IOException {
        while (offset < data.length) {
            int length = data[offset] & 0xFF;
            if (length == 0) {
                return offset + 1;
            }
            if ((length & 0xC0) == 0xC0) {
                if (offset + 1 >= data.length) {
                    throw new IOException("truncated dns pointer");
                }
                return offset + 2;
            }
            if (length > 63) {
                throw new IOException("invalid dns label length");
            }
            offset += length + 1;
        }
        throw new IOException("truncated dns name");
    }

    private static String readName(byte[] data, int offset) throws IOException {
        StringBuilder name = new StringBuilder();
        int jumps = 0;
        while (offset < data.length) {
            int length = data[offset] & 0xFF;
            if (length == 0) {
                return name.toString();
            }
            if ((length & 0xC0) == 0xC0) {
                if (offset + 1 >= data.length) {
                    throw new IOException("truncated dns pointer");
                }
                offset = ((length & 0x3F) << 8) | (data[offset + 1] & 0xFF);
                if (++jumps > MAX_POINTER_JUMPS) {
                    throw new IOException("dns compression loop");
                }
                continue;
            }
            if (length > 63 || offset + 1 + length > data.length) {
                throw new IOException("invalid dns label");
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(data, offset + 1, length, StandardCharsets.US_ASCII));
            offset += length + 1;
        }
        throw new IOException("truncated dns name");
    }
private static ParsedAnswers parseAnswers(byte[] response, int type) throws IOException {
        ParsedAnswers result = new ParsedAnswers();
        if (response.length < 12) {
            throw new IOException("short dns response");
        }
        int flags = readShort(response, 2);
        if ((flags & 0x8000) == 0) {
            throw new IOException("dns message is not a response");
        }
        if ((flags & 0x000F) == RCODE_NXDOMAIN) {
            return result;
        }
        int questions = readShort(response, 4);
        int answers = readShort(response, 6);

        int offset = 12;
        for (int i = 0; i < questions; i++) {
            offset = skipName(response, offset) + 4;
            if (offset > response.length) {
                throw new IOException("truncated dns question section");
            }
        }

        long minTtlSeconds = Long.MAX_VALUE;
        for (int i = 0; i < answers; i++) {
            if (offset >= response.length) {
                break;
            }
            offset = skipName(response, offset);
            if (offset + 10 > response.length) {
                throw new IOException("truncated dns answer");
            }
            int answerType = readShort(response, offset);
            int answerClass = readShort(response, offset + 2);
            long ttl = ((long) readShort(response, offset + 4) << 16) | readShort(response, offset + 6);
            int rdLength = readShort(response, offset + 8);
            offset += 10;
            if (offset + rdLength > response.length) {
                throw new IOException("truncated dns record data");
            }
            if (answerClass == CLASS_IN) {
                if (ttl < minTtlSeconds) {
                    minTtlSeconds = ttl;
                }
                if (answerType == type && type == TYPE_A && rdLength == 4) {
                    byte[] raw = new byte[4];
                    System.arraycopy(response, offset, raw, 0, 4);
                    result.addresses.add(InetAddress.getByAddress(null, raw));
                } else if (answerType == type && type == TYPE_AAAA && rdLength == 16) {
                    byte[] raw = new byte[16];
                    System.arraycopy(response, offset, raw, 0, 16);
                    result.addresses.add(InetAddress.getByAddress(null, raw));
                } else if (answerType == TYPE_CNAME) {
                    result.cname = readName(response, offset).toLowerCase(Locale.ROOT);
                }
            }
            offset += rdLength;
        }
        if (minTtlSeconds != Long.MAX_VALUE) {
            result.ttlMillis = Math.min(Math.max(minTtlSeconds * 1000L, CACHE_MIN_TTL_MS), CACHE_MAX_TTL_MS);
        }
        return result;
    }
    private static List<InetSocketAddress> nameServers() {
        List<InetSocketAddress> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        readResolvConf(resolvPath(), out, seen);
        if (out.isEmpty()) {
            readResolvConf("/etc/resolv.conf", out, seen);
        }
        if (out.isEmpty()) {
            LOGGER.warn("e4all: no nameservers in {}, falling back to public dns", resolvPath());
            try {
                out.add(new InetSocketAddress(InetAddress.getByName("8.8.8.8"), DNS_PORT));
                out.add(new InetSocketAddress(InetAddress.getByName("1.1.1.1"), DNS_PORT));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void readResolvConf(String path, List<InetSocketAddress> out, Set<String> seen) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.startsWith("nameserver")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2 || !seen.add(parts[1])) {
                    continue;
                }
                out.add(new InetSocketAddress(InetAddress.getByName(parts[1]), DNS_PORT));
            }
        } catch (Exception e) {
            LOGGER.debug("e4all: could not read nameservers from {} ({})", path, e.toString());
        }
    }

    private static String resolvPath() {
        String path = System.getProperty("ext.net.resolvPath");
        return path == null || path.isBlank() ? null : path;
    }

    public static String describeResolver() {
        String path = resolvPath();
        if (path == null && Files.exists(Paths.get("/etc/resolv.conf"))) {
            path = "/etc/resolv.conf";
        }
        return "resolv.conf: " + (path == null ? "(none found, using public dns)" : path)
                + ", servers: " + nameServers();
    }
}

