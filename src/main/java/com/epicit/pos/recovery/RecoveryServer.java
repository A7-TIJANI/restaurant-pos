package com.epicit.pos.recovery;

import com.epicit.pos.recovery.RecoveryActions.Entry;
import com.epicit.pos.recovery.RecoveryActions.RecoveryException;
import com.epicit.pos.recovery.RecoveryActions.Restored;
import com.epicit.pos.startup.SingleInstanceGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NFR-6: the restore wizard's web server. Spring cannot start when the database will not open, so this is a tiny
 * stand-alone server on the JDK's built-in {@code com.sun.net.httpserver}: no Spring, no database, nothing that
 * could itself need the broken file. It shows one self-contained page ({@code /recovery/recovery.html}, no
 * external assets, NFR-1) and three endpoints.
 *
 * <p>Safety: bound to 127.0.0.1 on an OS-assigned port; every request must carry a loopback {@code Host} header
 * (defeats DNS-rebinding from a web page); every POST must carry the random per-run token that only the served
 * page knows; requests are handled one at a time. No PIN is asked because the PIN lives in the broken database;
 * the control is the same as for the PIN reset (A14): physical access to the laptop. Nothing is ever deleted: a
 * restore keeps the broken file as {@code epicit-pos.mv.db.before-restore-<timestamp>}.
 *
 * <p>The server also answers {@code /ping} and writes {@code port.txt}, so a second double-click on the exe finds
 * this wizard through the single-instance guard (A17) instead of starting a second copy.
 */
public final class RecoveryServer implements AutoCloseable {

    static final String TOKEN_HEADER = "X-Recovery-Token";
    private static final String TOKEN_PLACEHOLDER = "%%TOKEN%%";
    private static final String PAGE_RESOURCE = "/recovery/recovery.html";
    /** Backup names are plain file names, so a field without quotes or backslashes is all that is ever valid. */
    private static final Pattern NAME_FIELD = Pattern.compile("\"name\"\\s*:\\s*\"([^\"\\\\]*)\"");
    private static final int MAX_BODY_BYTES = 2048;

    private final RecoveryActions actions;
    private final String problem;
    private final Path portFile;
    private final String token = new BigInteger(160, new SecureRandom()).toString(36);
    private final String page;
    private final HttpServer server;
    private final ExecutorService executor;
    private final int port;
    /** true = a backup was restored and the app should start normally; false = the person chose to quit. */
    private final CompletableFuture<Boolean> outcome = new CompletableFuture<>();

    public static RecoveryServer start(RecoveryActions actions, String problem, Path portFile) throws IOException {
        return new RecoveryServer(actions, problem, portFile);
    }

    private RecoveryServer(RecoveryActions actions, String problem, Path portFile) throws IOException {
        this.actions = actions;
        this.problem = problem == null ? "" : problem;
        this.portFile = portFile;
        this.page = loadPage().replace(TOKEN_PLACEHOLDER, token);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "recovery-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handlePage);
        server.createContext("/ping", ex -> {
            send(ex, 200, "text/plain; charset=utf-8", SingleInstanceGuard.PING_RESPONSE);
            ex.close();
        });
        server.createContext("/api/recovery/", this::handleApi);
        server.start();

        try {
            Files.createDirectories(portFile.toAbsolutePath().getParent());
            Files.writeString(portFile, Integer.toString(port));
        } catch (IOException e) {
            // only the single-instance guard suffers; the wizard itself works
            System.err.println("Could not write " + portFile + ": " + e.getMessage());
        }
    }

    public int port() {
        return port;
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** The per-run secret the page sends back with every POST. Exposed for tests. */
    String token() {
        return token;
    }

    /** Blocks until a backup was restored (true) or the person chose to quit (false). */
    public boolean awaitOutcome() throws InterruptedException {
        try {
            return outcome.get();
        } catch (ExecutionException e) {
            return false;
        }
    }

    @Override
    public void close() {
        server.stop(1);   // lets the response that finished the wizard reach the browser first
        executor.shutdownNow();
        try {
            if (Files.isRegularFile(portFile) && Integer.toString(port).equals(Files.readString(portFile).trim())) {
                Files.deleteIfExists(portFile);
            }
        } catch (IOException ignored) {
            // a stale port.txt is harmless: the guard verifies the port with /ping
        }
    }

    // ---- handlers ------------------------------------------------------------------------------------------------

    private void handlePage(HttpExchange ex) throws IOException {
        try {
            if (!hostOk(ex)) {
                json(ex, 403, error("FORBIDDEN", "Unexpected host."));
            } else if (!"GET".equals(ex.getRequestMethod()) || !"/".equals(ex.getRequestURI().getPath())) {
                json(ex, 404, error("NOT_FOUND", "Not found."));
            } else {
                ex.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'");
                send(ex, 200, "text/html; charset=utf-8", page);
            }
        } finally {
            ex.close();
        }
    }

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            if (!hostOk(ex)) {
                json(ex, 403, error("FORBIDDEN", "Unexpected host."));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && "/api/recovery/status".equals(path)) {
                json(ex, 200, statusJson());
            } else if ("POST".equals(method) && ("/api/recovery/restore".equals(path) || "/api/recovery/quit".equals(path))) {
                if (!tokenOk(ex)) {
                    json(ex, 403, error("FORBIDDEN", "This page is out of date. Reload it and try again."));
                } else if (outcome.isDone()) {
                    json(ex, 409, error("FINISHED", "This wizard has already finished."));
                } else if ("/api/recovery/quit".equals(path)) {
                    json(ex, 200, "{\"quit\":true}");
                    outcome.complete(false);
                } else {
                    restore(ex);
                }
            } else {
                json(ex, 404, error("NOT_FOUND", "Not found."));
            }
        } catch (RuntimeException e) {
            System.err.println("Recovery wizard error: " + e);
            json(ex, 500, error("FAILED", "Something went wrong. Nothing was changed."));
        } finally {
            ex.close();
        }
    }

    private void restore(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (raw.length > MAX_BODY_BYTES) {
            json(ex, 413, error("TOO_LARGE", "The request is too large."));
            return;
        }
        Matcher m = NAME_FIELD.matcher(new String(raw, StandardCharsets.UTF_8));
        String name = m.find() ? m.group(1) : "";
        try {
            Restored r = actions.restore(name);
            json(ex, 200, "{\"restored\":true,\"restaurantName\":" + quote(r.restaurantName())
                    + ",\"orders\":" + r.orders() + "}");
            outcome.complete(true);   // after the response is written: main() then closes this server
        } catch (RecoveryException e) {
            json(ex, e.status(), error(e.code(), e.getMessage()));
        }
    }

    private String statusJson() {
        StringBuilder b = new StringBuilder();
        b.append("{\"problem\":").append(quote(problem))
                .append(",\"folder\":").append(quote(actions.backupFolder()))
                .append(",\"backups\":[");
        boolean first = true;
        for (Entry e : actions.backups()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("{\"name\":").append(quote(e.name()))
                    .append(",\"sizeBytes\":").append(e.sizeBytes())
                    .append(",\"modified\":").append(quote(String.valueOf(e.modified()))).append('}');
        }
        return b.append("]}").toString();
    }

    // ---- checks and plumbing -------------------------------------------------------------------------------------

    private boolean hostOk(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return ("127.0.0.1:" + port).equals(host) || ("localhost:" + port).equals(host);
    }

    private boolean tokenOk(HttpExchange ex) {
        String sent = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        return sent != null && MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static String loadPage() throws IOException {
        try (InputStream in = RecoveryServer.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + PAGE_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String error(String code, String message) {
        return "{\"error\":" + quote(code) + ",\"message\":" + quote(message) + "}";
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", body);
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** JSON string literal, quotes included. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return b.append('"').toString();
    }
}
