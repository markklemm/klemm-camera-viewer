package klemm.technology.camera;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.apache.commons.fileupload.MultipartStream;
import org.bytedeco.javacv.CanvasFrame;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * Java port of the Go tapo.Client: - Connects to http://{host}:8800/stream (unless port supplied) - Performs HTTP Digest authentication (tapo + vigi variants) - Derives AES-CBC key/iv from Key-Exchange + creds and decrypts parts - Sends JSON requests in
 * multipart/mixed boundary - Reads multipart parts from the device stream boundary and emits decrypted TS payload
 *
 * Based on: client.go.txt :contentReference[oaicite:1]{index=1}
 *
 * Target: modern JDK (works on 21+).
 */
public final class TapoStreamViewer implements Closeable {

    // ===== Public API =====

    @FunctionalInterface
    public interface TsPayloadHandler {
        void onDecryptedTsPayload(byte[] mpegTsBytes) throws Exception;
    }

    public static TapoStreamViewer dial(String rawUrl) throws Exception {
        URI uri = new URI(rawUrl);

        String scheme = Optional.ofNullable(uri.getScheme()).orElseThrow(() -> new IllegalArgumentException("Missing scheme"));
        if (!scheme.equals("tapo") && !scheme.equals("vigi")) {
            throw new IllegalArgumentException("Unsupported scheme: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("Missing host");

        int port = (uri.getPort() == -1) ? 8800 : uri.getPort();

        UserInfo            ui    = UserInfo.parse(uri.getRawUserInfo());
        Map<String, String> query = splitQuery(uri.getRawQuery());

        // Default stream endpoint is /stream :contentReference[oaicite:2]{index=2}
        URI httpUri = new URI("http", null, host, port, "/stream", null, null);

        TapoStreamViewer c = new TapoStreamViewer(scheme, httpUri, ui.username, ui.password, query);
        c.conn1 = c.newConn(); // mirrors Go Dial() creating first connection :contentReference[oaicite:3]{index=3}
        return c;
    }

    /** Mirrors SetupStream() – sends the preview/get request and stores session1. :contentReference[oaicite:4]{index=4} */
    public synchronized void setupStream() throws Exception {
        if (session1 != null && !session1.isBlank())
            return;
        session1 = request(conn1, requestJson.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mirrors Handle() loop but emits decrypted MPEG-TS payload bytes to handler. :contentReference[oaicite:5]{index=5}
     */
    public void handle(TsPayloadHandler handler) throws Exception {
        ensureConn1();

        System.err.println("Boundary is was: " + conn1.deviceBoundary);
        CommonsMultipartReader mp = new CommonsMultipartReader(conn1.in, conn1.deviceBoundary);

        while (true) {
            MultipartPart part = mp.nextPart();
            if (part == null) {
                System.err.println("No parts");
                break;
            }
            ;

            String ct = part.headers.getOrDefault("content-type", "");
            if (!"video/mp2t".equalsIgnoreCase(ct.trim()))
                continue;

//            recv += part.body.length;
            byte[] dec = decrypt(part.body);
            handler.onDecryptedTsPayload(dec);
        }

    }

    @Override
    public void close() throws IOException {
        IOException err = null;
        if (conn1 != null) {
            try {
                conn1.close();
            } catch (IOException e) {
                err = e;
            }
        }
        if (conn2 != null) {
            try {
                conn2.close();
            } catch (IOException ignored) {
            }
        }
        if (err != null)
            throw err;
    }

    // ===== Internal state (mirrors Go fields) =====

    private final String              brand;         // "tapo" or "vigi"
    private final URI                 httpStreamUri; // http://host:port/stream
    private final Map<String, String> query;

//    private final String rawUsername;
    private String       username;   // may be rewritten to admin/none per Go logic :contentReference[oaicite:6]{index=6}
    private String       password;

    private Conn conn1;
    private Conn conn2;

    private volatile AesCbcDecryptor decryptor;

    private String session1;
//    private String session2;

    private String requestJson; // built in newConn :contentReference[oaicite:7]{index=7}

//    private long recv;
//    private long send;

    private TapoStreamViewer(String brand, URI httpStreamUri, String username, String password, Map<String, String> query) throws Exception {
        this.brand         = brand;
        this.httpStreamUri = httpStreamUri;
        this.query         = query;

//        this.rawUsername = username;
        this.username    = (username == null) ? "" : username;
        this.password    = (password == null) ? "" : password;

        System.err.println("Brand:" + this.brand);
    }

    private void ensureConn1() {
        if (conn1 == null)
            throw new IllegalStateException("Not connected");
    }

    // ===== Connection + request building (newConn + dial equivalents) =====

    private Conn newConn() throws Exception {
        // Build POST /stream?deviceId=... with required Content-Type :contentReference[oaicite:8]{index=8}
        String pathAndQuery = "/stream";
        String deviceId     = query.get("deviceId");
        if (deviceId != null && !deviceId.isBlank()) {
            pathAndQuery += "?deviceId=" + urlEncode(deviceId);
        }

        HttpRequest req = new HttpRequest("POST", pathAndQuery, httpStreamUri.getHost(), httpStreamUri.getPort());
        req.headers.put("Content-Type", "multipart/mixed; boundary=--client-stream-boundary--");

        ConnDialResult d = dialWithDigest(req, brand, username, password);

        if (d.statusCode != 200) {
            d.conn.close();
            throw new IOException("HTTP " + d.statusCode + " " + d.reason);
        }

        // Create decryptor only once (like Go: if c.decrypt == nil then newDecrypter) :contentReference[oaicite:9]{index=9}
        if (decryptor == null) {
            createDecryptorFromKeyExchange(d.headers, brand, username, password);
        }

        // Build request JSON (channels + subtype mapping) :contentReference[oaicite:10]{index=10}
        String channel = Optional.ofNullable(query.get("channel")).filter(s -> !s.isBlank()).orElse("0");
        String subtype = Optional.ofNullable(query.get("subtype")).orElse("");
        subtype = switch (subtype) {
        case "", "0" -> "HD";
        case "1" -> "VGA";
        default -> subtype;
        };

        this.requestJson = """
                {"params":{"preview":{"audio":["default"],"channels":[%s],"resolutions":["%s"]},"method":"get"},"seq":1,"type":"request"}"""
                .formatted(channel, subtype);

        return d.conn;
    }

    /**
     * Equivalent to Go newDectypter() and its special cases. :contentReference[oaicite:11]{index=11}
     */
    private void createDecryptorFromKeyExchange(Map<String, String> headers, String brand, String usernameIn, String passwordIn) throws Exception {
        String exchange = headers.getOrDefault("key-exchange", "");
        String nonce    = between(exchange, "nonce=\"", "\"");

        System.err.println("exchange: " + exchange);
        System.err.println("nonce: " + nonce);

        String u = usernameIn == null ? "" : usernameIn;
        String p = passwordIn == null ? "" : passwordIn;

        // tapo + empty password => cloud password is encoded as hash of "username" in URL userinfo :contentReference[oaicite:12]{index=12}
        if ("tapo".equals(brand) && (p.isBlank())) {
            if (exchange.contains("encrypt_type=\"3\"")) {
                p = toUpperHex(sha256(u.getBytes(StandardCharsets.UTF_8)));
            } else {
                p = toUpperHex(md5(u.getBytes(StandardCharsets.UTF_8)));
            }
            u = "admin";
        }

        // username="none" CVE fallback :contentReference[oaicite:13]{index=13}
        if (exchange.contains("username=\"none\"")) {
            u = "none";
            p = "TPL075526460603";
        }

        byte[] key = md5((nonce + ":" + p).getBytes(StandardCharsets.UTF_8));
        byte[] iv  = md5((u + ":" + nonce).getBytes(StandardCharsets.UTF_8));

        this.username  = u;
        this.password  = p;
        this.decryptor = new AesCbcDecryptor(key, iv);

        System.err.println("username: " + username);
        System.err.println("password: " + password);
    }

    /**
     * Equivalent to Go Request(): writes a multipart JSON part and reads first JSON response with session_id. :contentReference[oaicite:14]{index=14}
     */
    private String request(Conn conn, byte[] jsonBody) throws Exception {
        ensureOpen(conn);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(("----client-stream-boundary--\r\n").getBytes(StandardCharsets.US_ASCII));
        buf.write(("Content-Type: application/json\r\n").getBytes(StandardCharsets.US_ASCII));
        buf.write(("Content-Length: " + jsonBody.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        buf.write(jsonBody);
        buf.write(("\r\n").getBytes(StandardCharsets.US_ASCII));

        byte[] payload = buf.toByteArray();
        conn.out.write(payload);
        conn.out.flush();
//        send += payload.length;

        System.err.println("Sent preview JSON request (" + payload.length + " bytes)");

//        System.err.println("Boundary was: " + conn.deviceBoundary);
//        conn.deviceBoundary = DEVICE_STREAM_BOUNDARY;
        System.err.println("Boundary was: " + conn.deviceBoundary);
        CommonsMultipartReader mp = new CommonsMultipartReader(conn.in, conn.deviceBoundary);

        while (true) {
            MultipartPart part = mp.nextPart();
            if (part == null) {
                System.err.println("No response part");
                return "";
            }

//            String ct = part.headers.getOrDefault("content-type", "");
            // likely "application/json"
            String text = new String(part.body, StandardCharsets.UTF_8);

            String sid = extractJsonString(text, "\"session_id\"");
            if (sid != null)
                return sid;
        }
    }

    private static void ensureOpen(Conn c) throws IOException {
        if (c == null)
            throw new IOException("No connection");
        if (c.socket.isClosed())
            throw new IOException("Connection closed");
    }

    private static void discardHttpBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.getOrDefault("transfer-encoding", "");
        String cl = headers.getOrDefault("content-length", "");

        if (te.toLowerCase(Locale.ROOT).contains("chunked")) {
            // minimal chunked discard
            while (true) {
                String line = readLineAscii(in);
                if (line == null)
                    return;
                System.out.println("Read:" + line);
                int    semi = line.indexOf(';');
                String hex  = (semi >= 0) ? line.substring(0, semi) : line;
                int    size = Integer.parseInt(hex.trim(), 16);
                if (size == 0) {
                    // trailing headers after last chunk
                    while (true) {
                        String trailer = readLineAscii(in);
                        if (trailer == null || trailer.isEmpty())
                            return;
                    }
                }
                in.readNBytes(size); // chunk data
                readLineAscii(in); // trailing CRLF
            }
        }

        int n = parseIntSafe(cl, 0);
        if (n > 0) {
            System.err.println("Read:" + new String(in.readNBytes(n)));
        }
    }

    private static String parseBoundary(String contentType) {
        if (contentType == null)
            return null;
        // very small parser: boundary=token or boundary="token"
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("boundary=");
        if (i < 0)
            return null;
        String b = contentType.substring(i + "boundary=".length()).trim();
        if (b.startsWith("\"")) {
            int j = b.indexOf('"', 1);
            if (j > 1)
                b = b.substring(1, j);
        } else {
            int j = b.indexOf(';');
            if (j > 0)
                b = b.substring(0, j).trim();
        }

        return b;
    }

    // ===== Digest auth (dial equivalent) =====

    private ConnDialResult dialWithDigest(HttpRequest req, String brand, String username, String password) throws Exception {
        Conn conn = Conn.connect(httpStreamUri.getHost(), httpStreamUri.getPort(), 10_000);

        // 1st request to get WWW-Authenticate Digest :contentReference[oaicite:16]{index=16}
        writeHttpRequest(conn.out, req, null);
        HttpResponse res1 = readHttpResponse(conn.in);
        String       ct   = res1.headers.get("content-type");
        System.err.println("Response after JSON request → Content-Type: " + ct);
        String newBoundary = parseBoundary(ct);
        System.err.println("New boundary after request: " + newBoundary);
        conn.deviceBoundary = newBoundary; // ← update it!

        discardHttpBody(conn.in, res1.headers);

        String auth = res1.headers.getOrDefault("www-authenticate", "");
        if (res1.statusCode != 401 || !auth.startsWith("Digest")) {
            conn.close();
            throw new IOException("tapo: wrong status: " + res1.statusCode + " " + res1.reason);
        }

        String u = username == null ? "" : username;
        String p = password == null ? "" : password;

        // tapo special: empty password => hash cloud password, then username=admin :contentReference[oaicite:17]{index=17}
        if ("tapo".equals(brand) && p.isBlank()) {
            if (auth.contains("encrypt_type=\"3\"")) {
                p = toUpperHex(sha256(u.getBytes(StandardCharsets.UTF_8)));
            } else {
                p = toUpperHex(md5(u.getBytes(StandardCharsets.UTF_8)));
            }
            u = "admin";
        } else if ("vigi".equals(brand) && "admin".equals(u)) {
            // vigi admin password encoding :contentReference[oaicite:18]{index=18}
            p = securityEncode(p);
        }

        String realm = between(auth, "realm=\"", "\"");
        String nonce = between(auth, "nonce=\"", "\"");
        String qop   = between(auth, "qop=\"", "\"");
        String uri   = req.pathAndQuery;

        String ha1      = hexMd5(u + ":" + realm + ":" + p);
        String ha2      = hexMd5(req.method + ":" + uri);
        String nc       = "00000001";
        String cnonce   = randHex(32);                                                                // matches spirit of core.RandString(32,64) :contentReference[oaicite:19]{index=19}
        String response = hexMd5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        String header = "Digest username=\"" + u + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\"" + uri +
                "\", qop=" + qop + ", nc=" + nc + ", cnonce=\"" + cnonce + "\", response=\"" + response + "\"";

        String opaque = between(auth, "opaque=\"", "\"");
        if (opaque != null && !opaque.isBlank()) {
            header += ", opaque=\"" + opaque + "\", algorithm=MD5";
        }

        // 2nd request with Authorization :contentReference[oaicite:20]{index=20}
        Map<String, String> extraHeaders = Map.of("Authorization", header);
        writeHttpRequest(conn.out, req, extraHeaders);
        HttpResponse res2 = readHttpResponse(conn.in);

        String boundary = parseBoundary(res2.headers.get("Content-Type".toLowerCase()));
        conn.deviceBoundary = boundary;

        System.err.println("200 content-type: " + res2.headers.get("content-type"));
        System.err.println("parsed boundary: " + boundary);

        return new ConnDialResult(conn, res2.statusCode, res2.reason, res2.headers);
    }

    // ===== Multipart streaming reader (replacement for multipart.NewReader(conn, boundary)) =====

    public class CommonsMultipartReader {
        private final MultipartStream mp;
        private boolean               first = true;
        private boolean               done  = false;

        CommonsMultipartReader(InputStream in, String boundaryToken) {
            String b = boundaryToken == null ? "" : boundaryToken.trim();

            // Commons expects token without leading "--"
//            if (b.startsWith("--")) b = b.substring(2);

            System.err.println("Boundary is becomes: " + b);

            // Safety: if your constants include trailing "--" as part of token, KEEP IT
            // because the token is literally what appears after the leading boundary prefix.
            // Example delimiter line: "--" + token
            //
            // In your code DEVICE_STREAM_BOUNDARY is "--device-stream-boundary--"
            // That includes leading "--" already, so after stripping we pass "device-stream-boundary--".
            this.mp = new MultipartStream(in, b.getBytes(StandardCharsets.US_ASCII), 8 * 1024, null);
        }

        MultipartPart nextPart() throws IOException {
            if (done)
                return null;

            boolean hasNext;
            if (first) {
                // Skip preamble, position on first boundary
                hasNext = mp.skipPreamble();
                first   = false;
                if (!hasNext) {
                    done = true;
                    return null;
                }
            } else {
                hasNext = mp.readBoundary();
                if (!hasNext) {
                    done = true;
                    return null;
                }
            }

            // Headers are ASCII lines ending with CRLF, terminated by CRLFCRLF
            String              rawHeaders = mp.readHeaders();
            Map<String, String> headers    = parseHeadersCaseInsensitive(rawHeaders);

            // Read body until next boundary into a buffer (safe for JSON; TS parts can be larger)
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            mp.readBodyData(body);

            return new MultipartPart(headers, body.toByteArray());
        }

        private static Map<String, String> parseHeadersCaseInsensitive(String raw) {
            Map<String, String> m = new LinkedHashMap<>();
            if (raw == null)
                return m;
            for (String line : raw.split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx <= 0)
                    continue;
                String k = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String v = line.substring(idx + 1).trim();
                m.put(k, v);
            }
            return m;
        }
    }

    // Simple immutable part holder for this parser
    public class MultipartPart {
        final Map<String, String> headers; // lowercase keys
        final byte[]              body;

        MultipartPart(Map<String, String> headers, byte[] body) {
            this.headers = headers;
            this.body    = body;
        }
    }

    // ===== AES-CBC decryptor (Go recreates IV each decrypt call via SetIV) =====

    private static final class AesCbcDecryptor {
        private final byte[] key; // 16 bytes
        private final byte[] iv;  // 16 bytes

        AesCbcDecryptor(byte[] key, byte[] iv) {
            this.key = Arrays.copyOf(key, key.length);
            this.iv  = Arrays.copyOf(iv, iv.length);
        }

        byte[] decrypt(byte[] ciphertext) throws Exception {
            // Go decrypts in-place, AES-CBC, then PKCS#7 unpad. :contentReference[oaicite:22]{index=22}
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(ciphertext);

            int n   = plain.length;
            int pad = plain[n - 1] & 0xFF;
            if (pad <= 0 || pad > 16 || pad > n)
                return plain; // tolerate bad padding
            return Arrays.copyOf(plain, n - pad);
        }
    }

    private byte[] decrypt(byte[] b) throws Exception {
        AesCbcDecryptor d = decryptor;
        if (d == null)
            throw new IllegalStateException("Decryptor not initialized");
        return d.decrypt(b);
    }

    // ===== Vigi securityEncode (ported from Go) ===== :contentReference[oaicite:23]{index=23}

    private static final String KEY_SHORT = "RDpbLfCPsJZ7fiv";
    private static final String KEY_LONG  = "yLwVl0zKqws7LgKPRQ84Mdt708T1qQ3Ha7xv3H7NyU84p21BriUWBU43odz3iP4rBL3cD02KZciXTysVXiV8ngg6vL48rPJyAUw0HurW20xqxv9aYb4M9wK1Ae0wlro510qXeU07kV57fQMc8L6aLgMLwygtc0F10a0Dg70TOoouyFhdysuRMO51yY5ZlOZZLEal1h0t9YQW0Ko7oBwmCAHoic4HYbUyVeU3sfQ1xtXcPcf1aT303wAQhv66qzW";

    private static String securityEncode(String s) {
        int    size = s.length();
        int    n    = Math.max(size, KEY_SHORT.length());
        byte[] out  = new byte[n];

        for (int i = 0; i < n; i++) {
            int c1 = 187;
            int c2 = 187;
            if (i >= size) {
                c1 = KEY_SHORT.charAt(i);
            } else if (i >= KEY_SHORT.length()) {
                c2 = s.charAt(i);
            } else {
                c1 = KEY_SHORT.charAt(i);
                c2 = s.charAt(i);
            }
            out[i] = (byte) KEY_LONG.charAt((c1 ^ c2) % KEY_LONG.length());
        }
        return new String(out, StandardCharsets.ISO_8859_1);
    }

    // ===== Minimal HTTP over Socket (write request, parse response) =====

    private static final class Conn implements Closeable {
        final Socket       socket;
        final InputStream  in;
        final OutputStream out;

        String deviceBoundary;

        private Conn(Socket socket) throws IOException {
            this.socket = socket;
            this.in     = new BufferedInputStream(socket.getInputStream());
            this.out    = new BufferedOutputStream(socket.getOutputStream());
        }

        static Conn connect(String host, int port, int timeoutMs) throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(0); // streaming
            s.setTcpNoDelay(true);
            return new Conn(s);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class ConnDialResult {
        final Conn                conn;
        final int                 statusCode;
        final String              reason;
        final Map<String, String> headers;

        ConnDialResult(Conn conn, int statusCode, String reason, Map<String, String> headers) {
            this.conn       = conn;
            this.statusCode = statusCode;
            this.reason     = reason;
            this.headers    = headers;
        }
    }

    private static final class HttpRequest {
        final String              method;
        final String              pathAndQuery;
        final String              host;
        final int                 port;
        final Map<String, String> headers = new LinkedHashMap<>();

        HttpRequest(String method, String pathAndQuery, String host, int port) {
            this.method       = method;
            this.pathAndQuery = pathAndQuery;
            this.host         = host;
            this.port         = port;
        }
    }

    private static final class HttpResponse {
        final int                 statusCode;
        final String              reason;
        final Map<String, String> headers;

        HttpResponse(int statusCode, String reason, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.reason     = reason;
            this.headers    = headers;
        }
    }

    private static void writeHttpRequest(OutputStream out, HttpRequest req, Map<String, String> extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(req.method).append(' ').append(req.pathAndQuery).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(req.host).append(':').append(req.port).append("\r\n");
        sb.append("Connection: keep-alive\r\n");

        for (var e : req.headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        if (extraHeaders != null) {
            for (var e : extraHeaders.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static HttpResponse readHttpResponse(InputStream in) throws IOException {
        String statusLine = readLineAscii(in);
        if (statusLine == null) {
            System.err.println("No status line");
            statusLine = "";
        } else {
            System.err.println(statusLine);
        }

        // HTTP/1.1 401 Unauthorized
        String[] parts  = statusLine.split(" ", 3);
        int      code   = (parts.length >= 2) ? parseIntSafe(parts[1], -1) : -1;
        String   reason = (parts.length >= 3) ? parts[2] : "";

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLineAscii(in);
            if (line == null)
                break;
            if (line.isEmpty())
                break;
            int idx = line.indexOf(':');
            if (idx > 0) {
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                // Combine repeated headers if needed (simple approach: last wins)
                headers.put(k.toLowerCase(Locale.ROOT), v);
            }
        }
        return new HttpResponse(code, reason, headers);
    }

    private static String readLineAscii(InputStream in) throws IOException {
        ByteArrayOutputStream buf  = new ByteArrayOutputStream();
        int                   prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (buf.size() == 0)
                    return null;
                break;
            }
            if (prev == '\r' && b == '\n') {
                byte[] arr = buf.toByteArray();
                // remove trailing \r
                int len = arr.length;
                if (len > 0 && arr[len - 1] == '\r')
                    len--;
                return new String(arr, 0, len, StandardCharsets.US_ASCII);
            }
            buf.write(b);
            prev = b;
        }
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII).trim();
    }

    // ===== Helpers (hashing, parsing, query, etc.) =====

    private static byte[] md5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return md.digest(data);
    }

    private static byte[] sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    private static String hexMd5(String s) throws Exception {
        byte[] d = md5(s.getBytes(StandardCharsets.UTF_8));
        return toLowerHex(d);
    }

    private static String toLowerHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b)
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    private static String toUpperHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.toUpperCase(Character.forDigit((x >> 4) & 0xF, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(x & 0xF, 16)));
        }
        return sb.toString();
    }

    private static String between(String s, String left, String right) {
        if (s == null)
            return "";
        int i = s.indexOf(left);
        if (i < 0)
            return "";
        int j = s.indexOf(right, i + left.length());
        if (j < 0)
            return "";
        return s.substring(i + left.length(), j);
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String randHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return toLowerHex(b);
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static Map<String, String> splitQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank())
            return map;
        for (String kv : rawQuery.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0)
                map.put(urlDecode(kv), "");
            else
                map.put(urlDecode(kv.substring(0, i)), urlDecode(kv.substring(i + 1)));
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String extractJsonString(String json, String key) {
        // Very small helper: finds `"session_id":"..."`
        int k = json.indexOf(key);
        if (k < 0)
            return null;
        int colon = json.indexOf(':', k + key.length());
        if (colon < 0)
            return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0)
            return null;
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0)
            return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    private static final class UserInfo {
        final String username;
        final String password;

        UserInfo(String username, String password) {
            this.username = username;
            this.password = password;
        }

        static UserInfo parse(String rawUserInfo) {
            if (rawUserInfo == null || rawUserInfo.isBlank())
                return new UserInfo("", "");
            int idx = rawUserInfo.indexOf(':');
            if (idx < 0)
                return new UserInfo(urlDecode(rawUserInfo), "");
            return new UserInfo(urlDecode(rawUserInfo.substring(0, idx)), urlDecode(rawUserInfo.substring(idx + 1)));
        }
    }

    // ===== Usage example =====
    public static void main(String[] args) throws Exception {
        start(args[1], args[0], 1);
    }
    
    
    public static void start(final String cloudPassword, final String ipAddress, final int cameraNumber) throws IOException, Exception {

        try (TapoStreamViewer c = TapoStreamViewer.dial("tapo://" + cloudPassword + "@" + ipAddress)) {

            try {
                Thread.sleep(100);
            } catch (Exception e) {}
            
            c.setupStream();

            System.err.println("Session ID: " + c.session1);

            try {
                Thread.sleep(100);
            } catch (Exception e) {}

            // Create the pipe once (before starting handle)
            PipedOutputStream pipedOut = new PipedOutputStream();
            PipedInputStream  pipedIn  = new PipedInputStream(pipedOut, 1024 * 1024); // 1 MB buffer – tune as needed

            // Start FFmpegFrameGrabber in a background thread (or your UI thread)
            new Thread(() -> {
                System.err.println("Starting thread");
                try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(pipedIn)) {

                    grabber.setFormat("mpegts");

                    grabber.setOption("probesize",       "500000");      // ~0.5 MB – enough to catch initial tables
                    grabber.setOption("analyzeduration", "5000000");     // 5 seconds in µs – give it time to see SPS/PPS

                    grabber.setVideoCodecName("h264");
                    grabber.setOption("framerate", "25");                // common Tapo default (try 15, 20, 30 if needed)
                    grabber.setOption("video_size", "1920x1080");        // ← most important if you know the resolution
                    // or try: "1280x720", "2560x1440", "3840x2160" depending on your model

                    // Optional helpers for TS streams that start mid-stream
                    grabber.setOption("fflags", "+genpts+igndts+discardcorrupt");
                    grabber.setOption("max_delay", "500000");            // 0.5s
                    grabber.setOption("strict", "experimental");

                    grabber.start(false); 
                    
                    System.err.println("Grabber started OK");
                    System.err.println("  Video codec     : " + grabber.getVideoCodecName());
                    System.err.println("  Audio codec     : " + grabber.getAudioCodecName());
                    System.err.println("  Frame rate      : " + grabber.getFrameRate());
                    System.err.println("  Image width/height: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());
                    
                    System.err.println("Grabber started – width: " + grabber.getImageWidth() +
                            ", height: " + grabber.getImageHeight());

                    // Video display setup
                    final CanvasFrame canvas = new CanvasFrame("Klemm Camera: " + cameraNumber, CanvasFrame.getDefaultGamma() / grabber.getGamma());

                    try {
                        // Load the icon image from the resources folder
                        InputStream iconStream = RtspStreamViewer.class.getClassLoader().getResourceAsStream("security-camera.png");

                        if (iconStream != null) {
                            BufferedImage iconImage = ImageIO.read(iconStream);
                            canvas.setIconImage(iconImage);
                        } else {
                            System.err.println("Icon image not found in resources.");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    canvas.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    canvas.setCanvasSize(960, 540); // 1920x1080 / 2

                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            canvas.setVisible(true);
                        }
                    });

                    System.err.println("Canvas init complete");

                    // Stream video and audio
                    Frame frame;
                    while (canvas.isVisible() && (frame = grabber.grab()) != null) {
                        // Display video frames
                        if (frame.image != null) {
                            canvas.showImage(frame);
                        }
                    }

                    System.out.println("Finished");

                    grabber.stop();
                    canvas.dispose();

                    System.out.println("Cleaned up");

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();

            // Now feed the decrypted chunks into the pipe as they arrive
            c.handle(ts -> {
                try {
                    pipedOut.write(ts);
                    pipedOut.flush(); // important – helps FFmpeg see data promptly
//                    System.err.println("Piped " + ts.length + " bytes");
                } catch (Exception e) {
                    e.printStackTrace();
                    // If pipe is broken (e.g. grabber closed), you can close pipedOut
                }
            });

            // When you want to stop (e.g. close button / end of stream)
            pipedOut.close(); // signals end-of-stream to grabber
        }
    }
}