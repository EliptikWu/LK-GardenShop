package dev.lk.gardenshop.pack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedPackServerTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private final EmbeddedPackServer server = new EmbeddedPackServer(LOGGER);

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /** A port the OS just told us was free, so the test does not collide with a real service. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] fetch(int port) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI
                .create("http://127.0.0.1:" + port + EmbeddedPackServer.PATH)
                .toURL().openConnection();
        connection.setRequestMethod("GET");
        // Without these a stalled socket hangs the whole test task rather than failing it, which
        // turns a five-second build into an unbounded one.
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        assertThat(connection.getResponseCode()).isEqualTo(200);
        assertThat(connection.getContentType()).isEqualTo("application/zip");
        try (var body = connection.getInputStream()) {
            return body.readAllBytes();
        }
    }

    @Test
    @DisplayName("serves the pack bytes exactly")
    void servesThePack(@TempDir Path folder) throws IOException {
        byte[] payload = "not really a zip, but the bytes are what matter".getBytes(StandardCharsets.UTF_8);
        Path pack = folder.resolve("pack.zip");
        Files.write(pack, payload);

        int port = freePort();
        assertThat(server.start(pack.toFile(), port)).isTrue();
        assertThat(server.isRunning()).isTrue();

        assertThat(fetch(port)).isEqualTo(payload);
    }

    @Test
    @DisplayName("a taken port fails soft rather than throwing")
    void occupiedPortDoesNotThrow(@TempDir Path folder) throws IOException {
        Path pack = folder.resolve("pack.zip");
        Files.write(pack, new byte[]{1, 2, 3});

        // Held open for the duration, so the server cannot possibly bind it.
        try (ServerSocket blocker = new ServerSocket(0)) {
            // A server that threw here would take the whole plugin down over a port clash, which
            // is a wildly disproportionate response to losing a cosmetic feature.
            assertThat(server.start(pack.toFile(), blocker.getLocalPort())).isFalse();
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    @DisplayName("a missing pack file fails soft too")
    void missingFileDoesNotThrow(@TempDir Path folder) throws IOException {
        assertThat(server.start(folder.resolve("absent.zip").toFile(), freePort())).isFalse();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop is idempotent, so a double disable is harmless")
    void stopIsIdempotent(@TempDir Path folder) throws IOException {
        Path pack = folder.resolve("pack.zip");
        Files.write(pack, new byte[]{7});

        server.start(pack.toFile(), freePort());
        server.stop();
        server.stop();

        assertThat(server.isRunning()).isFalse();
    }

    @Test
    @DisplayName("the port is released on stop, so the next enable can bind it again")
    void portIsReleased(@TempDir Path folder) throws IOException {
        Path pack = folder.resolve("pack.zip");
        Files.write(pack, new byte[]{9});
        int port = freePort();

        assertThat(server.start(pack.toFile(), port)).isTrue();
        server.stop();

        // A leaked port would make every /reload of the server fail to re-serve the pack.
        EmbeddedPackServer second = new EmbeddedPackServer(LOGGER);
        try {
            assertThat(second.start(pack.toFile(), port)).isTrue();
        } finally {
            second.stop();
        }
    }
}
