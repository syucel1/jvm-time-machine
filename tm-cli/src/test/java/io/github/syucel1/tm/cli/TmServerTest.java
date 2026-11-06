package io.github.syucel1.tm.cli;

import io.github.syucel1.tm.core.jfr.JfrReader;
import io.github.syucel1.tm.core.model.JvmRecording;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Phase 3 exit gate over HTTP: the JSON API answers questions about demo.jfr. */
class TmServerTest {

    private static final String DEMO = "io.github.syucel1.tm.demo.DemoApp";

    private static TmServer server;
    private static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        String prop = System.getProperty("tm.demoJfr");
        boolean present = prop != null && Files.isRegularFile(Path.of(prop));
        if (System.getenv("CI") != null) {
            assertTrue(present, "demo.jfr must exist in CI: " + prop);
        }
        assumeTrue(present, "demo.jfr not found; run ./gradlew :demo:recordDemo");
        JvmRecording rec = JfrReader.create().read(Path.of(prop));
        server = new TmServer(rec, "127.0.0.1", 0);
        server.start();
        client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(server.url() + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void recordingEndpointDescribesTheFile() throws Exception {
        HttpResponse<String> r = get("api/recording");
        assertEquals(200, r.statusCode());
        assertEquals("application/json", r.headers().firstValue("Content-Type").orElse(""));
        assertTrue(r.body().contains("\"source\":\"demo.jfr\""), r.body());
        assertTrue(r.body().contains("\"cpu-worker\""));
    }

    @Test
    void overviewReturnsPerSecondBuckets() throws Exception {
        HttpResponse<String> r = get("api/overview?from=1&to=3");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"bucketSeconds\":1"), r.body());
        assertTrue(r.body().contains("\"index\":1,") && r.body().contains("\"index\":2,"), r.body());
        assertTrue(!r.body().contains("\"index\":3,"), r.body());
        assertTrue(r.body().contains("\"cpuSamples\":"));
    }

    @Test
    void windowAnswersHotMethodsLocksAllocationsAndGc() throws Exception {
        HttpResponse<String> r = get("api/window?top=5");
        assertEquals(200, r.statusCode());
        String body = r.body();
        assertTrue(body.contains(DEMO + ".slowPrimeCount"), "hot methods");
        assertTrue(body.contains("\"method\":\"" + DEMO + ".contended\""), "contention");
        assertTrue(body.contains("\"allocationsBySite\":[{\"name\":\"" + DEMO), "allocation sites");
        assertTrue(body.contains("\"gc\":{\"count\":"), "gc");
    }

    @Test
    void badInputGives400WithJsonError() throws Exception {
        assertEquals(400, get("api/window?from=abc").statusCode());
        assertEquals(400, get("api/window?from=5&to=2").statusCode());
        assertEquals(400, get("api/window?top=0").statusCode());
        HttpResponse<String> r = get("api/nope");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().startsWith("{\"error\":"), r.body());
    }

    @Test
    void indexPageLinksTheApi() throws Exception {
        HttpResponse<String> r = get("");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("api/window"));
    }
}
