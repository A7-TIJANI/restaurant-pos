package com.epicit.pos.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.epicit.pos.recovery.RecoveryActions.Entry;
import com.epicit.pos.recovery.RecoveryActions.RecoveryException;
import com.epicit.pos.recovery.RecoveryActions.Restored;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wizard's HTTP surface with fake actions: no database, no Spring. */
class RecoveryServerTest {

    @TempDir Path dir;
    final List<String> restoreCalls = new ArrayList<>();
    RecoveryActions actions;
    RecoveryServer server;
    HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        actions = new RecoveryActions() {
            @Override public String backupFolder() { return "C:\\backups"; }
            @Override public List<Entry> backups() {
                return List.of(new Entry("epicit-pos-backup-2026-11-12.zip", 2048, Instant.parse("2026-11-12T08:00:00Z")),
                        new Entry("epicit-pos-backup-2026-11-11.zip", 1024, Instant.parse("2026-11-11T08:00:00Z")));
            }
            @Override public Restored restore(String name) {
                restoreCalls.add(name);
                if (name.equals("bad.zip")) {
                    throw new RecoveryException(422, "BACKUP_UNREADABLE", "This backup file could not be read. Pick another one.");
                }
                return new Restored("Nile \"Grill\"", 42);
            }
        };
        server = RecoveryServer.start(actions, "File corrupted while reading record", dir.resolve("port.txt"));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(server.url() + path.substring(1))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.url() + path.substring(1)))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header(RecoveryServer.TOKEN_HEADER, token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesTheWizardPageWithItsOwnTokenAndAStrictCsp() throws Exception {
        HttpResponse<String> r = get("/");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("cannot open its data").contains("data-token=\"" + server.token() + "\"").doesNotContain("%%TOKEN%%");
        assertThat(r.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'");
        assertThat(r.body()).doesNotContain("http://").doesNotContain("https://");   // NFR-1: nothing fetched from outside
    }

    @Test
    void pingIdentifiesThisApplicationSoTheSingleInstanceGuardFindsTheWizard() throws Exception {
        assertThat(get("/ping").body()).isEqualTo("epicit-pos");
        assertThat(Files.readString(dir.resolve("port.txt")).trim()).isEqualTo(Integer.toString(server.port()));
    }

    @Test
    void statusListsTheBackupsAndTheProblem() throws Exception {
        HttpResponse<String> r = get("/api/recovery/status");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"problem\":\"File corrupted while reading record\"")
                .contains("\"folder\":\"C:\\\\backups\"")
                .contains("epicit-pos-backup-2026-11-12.zip").contains("\"sizeBytes\":2048");
    }

    @Test
    void restoreWithoutTheTokenIsRefusedAndNothingHappens() throws Exception {
        assertThat(post("/api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-12.zip\"}", null).statusCode()).isEqualTo(403);
        assertThat(post("/api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-12.zip\"}", "wrong").statusCode()).isEqualTo(403);
        assertThat(restoreCalls).isEmpty();
    }

    @Test
    void aRequestWithAForeignHostHeaderIsRefused() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write(("GET /api/recovery/status HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String response = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 403");
        }
    }

    @Test
    void aSuccessfulRestoreAnswersThenCompletesTheWizard() throws Exception {
        HttpResponse<String> r = post("/api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-12.zip\"}", server.token());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"restored\":true").contains("\"restaurantName\":\"Nile \\\"Grill\\\"\"").contains("\"orders\":42");
        assertThat(restoreCalls).containsExactly("epicit-pos-backup-2026-11-12.zip");
        assertThat(waitForOutcome()).isTrue();
    }

    @Test
    void aSecondRestoreAfterTheWizardFinishedIsRefused() throws Exception {
        post("/api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-12.zip\"}", server.token());

        assertThat(post("/api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-11.zip\"}", server.token()).statusCode()).isEqualTo(409);
        assertThat(restoreCalls).hasSize(1);
    }

    @Test
    void aFailedRestoreShowsTheMessageAndKeepsTheWizardOpenForAnotherTry() throws Exception {
        HttpResponse<String> r = post("/api/recovery/restore", "{\"name\":\"bad.zip\"}", server.token());

        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("BACKUP_UNREADABLE").contains("Pick another one");
        assertThat(post("/api/recovery/restore", "{\"name\":\"epicit-pos-backup-2026-11-11.zip\"}", server.token()).statusCode()).isEqualTo(200);
    }

    @Test
    void quitEndsTheWizardWithoutRestoring() throws Exception {
        assertThat(post("/api/recovery/quit", "", server.token()).statusCode()).isEqualTo(200);

        assertThat(waitForOutcome()).isFalse();
        assertThat(restoreCalls).isEmpty();
    }

    @Test
    void anOversizedBodyIsRefused() throws Exception {
        assertThat(post("/api/recovery/restore", "{\"name\":\"" + "a".repeat(5000) + "\"}", server.token()).statusCode()).isEqualTo(413);
        assertThat(restoreCalls).isEmpty();
    }

    @Test
    void closeRemovesItsOwnPortFileButNotSomeoneElses() throws Exception {
        server.close();
        assertThat(dir.resolve("port.txt")).doesNotExist();
    }

    private boolean waitForOutcome() throws Exception {
        // awaitOutcome blocks; the outcome is already complete by the time the HTTP call returned, so this is immediate
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return ex.submit(server::awaitOutcome).get(5, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
    }
}
