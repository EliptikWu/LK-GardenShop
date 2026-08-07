package dev.lk.gardenshop.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * A one-route HTTP server that hands out the resource pack, so the plugin can host its own art with
 * nothing for the owner to upload.
 *
 * <p>Built on the JDK's own {@code com.sun.net.httpserver}, which adds no dependency and no shading.
 * The zip is read into memory once at {@link #start} — a few kilobytes that only change between
 * plugin versions — so a join never touches the disk.
 *
 * <p><b>The port must be reachable from the internet</b> and is separate from the Minecraft port. On
 * a managed host that means requesting an extra allocation. Everything here is fail-soft: a port that
 * will not open logs and returns false rather than throwing, and the caller falls back to sending no
 * pack at all. A menu without art is a far better outcome than a server that will not start.
 */
public final class EmbeddedPackServer {

    /** The single path served. Clients are given this as their download URL. */
    public static final String PATH = "/pack.zip";

    private final Logger logger;

    private HttpServer server;
    private ExecutorService executor;

    public EmbeddedPackServer(Logger logger) {
        this.logger = logger;
    }

    /**
     * Binds the port and starts serving.
     *
     * @return {@code true} when listening; {@code false} if the port could not be opened
     */
    public boolean start(File packFile, int port) {
        if (server != null) {
            return true;
        }

        byte[] payload;
        try {
            payload = Files.readAllBytes(packFile.toPath());
        } catch (IOException e) {
            logger.warning("Could not read " + packFile + " to serve it: " + e.getMessage());
            return false;
        }

        try {
            // Bound on all interfaces: a managed host maps its allocated port to the container, and
            // binding to a specific address there usually gets the wrong one.
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            logger.warning("Could not open port " + port + " to serve the resource pack ("
                    + e.getMessage() + "). Set resource-pack.url to host the zip yourself, or ask "
                    + "your host for a free TCP allocation and put it in resource-pack.self-host.port.");
            server = null;
            return false;
        }

        // One thread: this serves a few KB per player join, not traffic.
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LKGardenShop-pack-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext(PATH, exchange -> respond(exchange, payload));
        server.start();

        logger.info(() -> "Serving the resource pack on port " + port + PATH
                + " (" + payload.length / 1024 + " KB).");
        return true;
    }

    private void respond(HttpExchange exchange, byte[] payload) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        } finally {
            exchange.close();
        }
    }

    /** Stops listening. Safe to call when never started. */
    public void stop() {
        if (server != null) {
            // No delay: the payload is in memory and a half-finished download will simply retry.
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return server != null;
    }
}
