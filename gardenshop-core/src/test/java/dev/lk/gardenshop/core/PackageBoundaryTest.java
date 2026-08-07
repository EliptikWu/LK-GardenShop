package dev.lk.gardenshop.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one architectural rule this module exists to uphold: the pricing
 * engine stays free of server APIs, so it can be tested in milliseconds without
 * booting Paper.
 *
 * <p>Without this test the boundary erodes by accident — someone reaches for
 * {@code ItemStack} inside a strategy "just this once", and from then on the whole
 * matrix test needs a server.
 */
class PackageBoundaryTest {

    /** Packages a pure-domain module must never mention. */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "org.bukkit",
            "io.papermc",
            "io.lumine",
            "net.milkbowl",
            "me.clip",
            "net.kyori",
            // The bundled item bridge is a Bukkit library like any other. Core deals in adapter
            // ids as strings, which needs none of its classes.
            "dev.lk.itembridge");

    @Test
    @DisplayName("no source file in gardenshop-core references a server API")
    void coreIsFreeOfServerApis() {
        Path sourceRoot = Path.of("src", "main", "java");
        assertThat(sourceRoot)
                .as("test must run with the module directory as its working directory")
                .isDirectory();

        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                for (String forbidden : FORBIDDEN_PACKAGES) {
                    if (source.contains(forbidden)) {
                        violations.add(sourceRoot.relativize(path) + " references " + forbidden);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(violations)
                .as("gardenshop-core must stay pure Java — move server-facing code to gardenshop-bukkit")
                .isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
