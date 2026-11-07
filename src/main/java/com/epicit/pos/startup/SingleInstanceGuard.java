package com.epicit.pos.startup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * A17: double-clicking the exe while the app is running must not start a second instance
 * (H2 file lock would crash it confusingly). If {@code port.txt} names a live Epic IT POS,
 * the caller opens the browser there and exits. A stale port.txt (crash, reused port) is ignored.
 */
public final class SingleInstanceGuard {

    /** Body of GET /ping - proves the port belongs to us and not to some other local server. */
    public static final String PING_RESPONSE = "epicit-pos";

    private SingleInstanceGuard() {
    }

    public static Optional<Integer> findRunningPort(Path portFile) {
        if (!Files.isRegularFile(portFile)) {
            return Optional.empty();
        }
        int port;
        try {
            port = Integer.parseInt(Files.readString(portFile).trim());
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
        if (port < 1 || port > 65535) {
            return Optional.empty();
        }
        return isEpicInstance(port) ? Optional.of(port) : Optional.empty();
    }

    static boolean isEpicInstance(int port) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping"))
                    .timeout(Duration.ofMillis(800))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && PING_RESPONSE.equals(response.body().trim());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
