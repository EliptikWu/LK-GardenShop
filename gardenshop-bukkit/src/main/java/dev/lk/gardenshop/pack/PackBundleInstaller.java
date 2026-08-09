package dev.lk.gardenshop.pack;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Puts the resource pack bundled in the jar onto disk, where the HTTP server can serve it.
 *
 * <h2>The upgrade problem this solves</h2>
 * Two obvious rules are both wrong. "Always overwrite" throws away an admin's edited pack on every
 * restart. "Never overwrite" means a plugin update ships new art that nobody ever sees.
 *
 * <p>So a marker file records the hash of the bundled pack that <em>this class</em> last wrote. On
 * each start there are three cases:
 *
 * <ul>
 *   <li>the file on disk matches the bundled one — nothing to do;</li>
 *   <li>it matches the marker — we wrote it and the plugin has since been updated, so replace it;</li>
 *   <li>it matches neither — an admin edited it, so keep theirs and say so.</li>
 * </ul>
 */
public final class PackBundleInstaller {

    /** Name of the pack inside the jar, put there by the {@code packZip} Gradle task. */
    private static final String JAR_RESOURCE = "pack.zip";

    private static final String FOLDER = "resourcepack";
    private static final String PACK_FILE = "pack.zip";
    private static final String MARKER_FILE = ".bundled-sha1";

    private final Plugin plugin;
    private final Logger logger;

    public PackBundleInstaller(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * The pack on disk and its hash.
     *
     * @param sha1       hex digest of the file as it actually is, which is what a client checks
     * @param customised whether this differs from the pack shipped in the jar
     */
    public record InstalledPack(File file, String sha1, boolean customised) {
    }

    /** @return the pack ready to serve, or empty if the jar carries none or it could not be written */
    public Optional<InstalledPack> install() {
        byte[] bundled;
        try (InputStream stream = plugin.getResource(JAR_RESOURCE)) {
            if (stream == null) {
                logger.warning("This build carries no " + JAR_RESOURCE
                        + " - run './gradlew packZip' before building to bundle the resource pack.");
                return Optional.empty();
            }
            bundled = stream.readAllBytes();
        } catch (IOException e) {
            logger.warning("Could not read the bundled resource pack: " + e.getMessage());
            return Optional.empty();
        }

        String bundledSha = sha1(bundled);
        Path folder = plugin.getDataFolder().toPath().resolve(FOLDER);
        Path pack = folder.resolve(PACK_FILE);
        Path marker = folder.resolve(MARKER_FILE);

        try {
            Files.createDirectories(folder);

            if (!Files.exists(pack)) {
                write(pack, marker, bundled, bundledSha);
                logger.info("Extracted the bundled resource pack to " + pack + ".");
                return Optional.of(new InstalledPack(pack.toFile(), bundledSha, false));
            }

            String diskSha = sha1(Files.readAllBytes(pack));
            if (diskSha.equals(bundledSha)) {
                // Already current. Refresh the marker in case it was deleted.
                Files.writeString(marker, bundledSha, StandardCharsets.UTF_8);
                return Optional.of(new InstalledPack(pack.toFile(), diskSha, false));
            }

            String markerSha = Files.exists(marker)
                    ? Files.readString(marker, StandardCharsets.UTF_8).trim()
                    : "";
            if (diskSha.equals(markerSha)) {
                write(pack, marker, bundled, bundledSha);
                logger.info("The bundled resource pack changed in this version; updated " + pack + ".");
                return Optional.of(new InstalledPack(pack.toFile(), bundledSha, false));
            }

            logger.info("Keeping your edited " + pack + " (it differs from the bundled pack). "
                    + "Delete it to go back to the version shipped with the plugin.");
            return Optional.of(new InstalledPack(pack.toFile(), diskSha, true));

        } catch (IOException e) {
            logger.warning("Could not install the resource pack to " + pack + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static void write(Path pack, Path marker, byte[] bytes, String sha1) throws IOException {
        Files.write(pack, bytes);
        Files.writeString(marker, sha1, StandardCharsets.UTF_8);
    }

    /** Hex SHA-1, the form both the marker file and the client protocol use. */
    public static String sha1(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is required of every JVM; if it is missing the platform is broken.
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    // A hex -> 20-raw-bytes converter used to live here, for Bukkit's byte[] hash parameter. The
    // delivery path now goes through Adventure's ResourcePackInfo, which takes the hex string
    // itself, so nothing called it any more and it was deleted rather than left as code whose only
    // remaining user was its own test.

}
