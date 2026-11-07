package com.epicit.pos.startup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceGuardTest {

    @TempDir Path dir;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private int startServer(String pingBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", ex -> {
            byte[] body = pingBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void noPortFileMeansNoRunningInstance() {
        assertThat(SingleInstanceGuard.findRunningPort(dir.resolve("port.txt"))).isEmpty();
    }

    @Test
    void liveEpicInstanceIsDetected() throws IOException {
        int port = startServer(SingleInstanceGuard.PING_RESPONSE);
        Path portFile = Files.writeString(dir.resolve("port.txt"), Integer.toString(port));
        assertThat(SingleInstanceGuard.findRunningPort(portFile)).contains(port);
    }

    @Test
    void stalePortFileIsIgnored() throws IOException {
        int port = startServer(SingleInstanceGuard.PING_RESPONSE);
        server.stop(0);
        server = null;
        Path portFile = Files.writeString(dir.resolve("port.txt"), Integer.toString(port));
        assertThat(SingleInstanceGuard.findRunningPort(portFile)).isEmpty();
    }

    @Test
    void someOtherLocalServerOnThatPortIsNotMistakenForUs() throws IOException {
        int port = startServer("something-else");
        Path portFile = Files.writeString(dir.resolve("port.txt"), Integer.toString(port));
        assertThat(SingleInstanceGuard.findRunningPort(portFile)).isEmpty();
    }

    @Test
    void garbagePortFileIsIgnored() throws IOException {
        Path portFile = Files.writeString(dir.resolve("port.txt"), "not-a-number");
        assertThat(SingleInstanceGuard.findRunningPort(portFile)).isEmpty();
    }
}
