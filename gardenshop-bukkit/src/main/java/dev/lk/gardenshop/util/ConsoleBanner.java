package dev.lk.gardenshop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * The startup banner.
 *
 * <p>Colour is the garnish; the point is the checklist. This plugin can start in half a dozen
 * partially-wired states — no economy, no MythicMobs, a weights file that did not load — and
 * each one only surfaces as a warning buried among a hundred other plugins' output. Printing
 * one block that answers "is everything actually hooked up?" turns that into a glance.
 *
 * <p>Sent through {@link ConsoleCommandSender} rather than the plugin logger: the logger is
 * plain {@code java.util.logging} and strips formatting, whereas the console sender renders
 * Adventure components as ANSI. On a terminal without colour support the codes are dropped
 * and the text still reads fine, so there is nothing to detect or fall back from.
 */
public final class ConsoleBanner {

    /** One line of the checklist. */
    public record Entry(Status status, String label, String detail) {

        public static Entry ok(String label, String detail) {
            return new Entry(Status.OK, label, detail);
        }

        public static Entry warn(String label, String detail) {
            return new Entry(Status.WARN, label, detail);
        }

        public static Entry off(String label, String detail) {
            return new Entry(Status.OFF, label, detail);
        }
    }

    public enum Status {
        /** Working. */
        OK,
        /** Working, but something is worth looking at. */
        WARN,
        /** Deliberately or unavoidably unavailable. */
        OFF
    }

    private final Messages messages;

    public ConsoleBanner(Messages messages) {
        this.messages = messages;
    }

    /**
     * Prints the banner, or a single summary line when the banner is switched off.
     *
     * @param entries the checklist, in display order
     * @param millis  how long enabling took
     */
    public void print(String version, List<Entry> entries, long millis, boolean enabled) {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (Component line : lines(version, entries, millis, enabled)) {
            console.sendMessage(line);
        }
    }

    /**
     * Builds the banner without sending it.
     *
     * <p>Separate from {@link #print} so a test can render the result and check that every
     * message key resolves and the columns line up — a banner is the one piece of output
     * nobody notices is broken, because a missing key just prints its own name.
     */
    public List<Component> lines(String version, List<Entry> entries, long millis, boolean enabled) {
        List<Component> out = new ArrayList<>();

        if (!enabled) {
            out.add(messages.get("console.summary",
                    Placeholder.unparsed("version", version),
                    Placeholder.unparsed("ms", Long.toString(millis)),
                    Placeholder.unparsed("problems", Integer.toString(countProblems(entries)))));
            return out;
        }

        out.addAll(messages.getList("console.header",
                Placeholder.unparsed("version", version),
                Placeholder.unparsed("server", Bukkit.getVersion()),
                Placeholder.unparsed("java", Integer.toString(Runtime.version().feature()))));

        for (Entry entry : entries) {
            out.add(messages.get("console.entry",
                    Placeholder.component("status", symbol(entry.status())),
                    Placeholder.unparsed("label", pad(entry.label())),
                    Placeholder.unparsed("detail", entry.detail())));
        }

        out.addAll(messages.getList("console.footer",
                Placeholder.unparsed("ms", Long.toString(millis)),
                Placeholder.unparsed("problems", Integer.toString(countProblems(entries)))));
        return out;
    }

    private Component symbol(Status status) {
        return switch (status) {
            case OK -> messages.get("console.status.ok");
            case WARN -> messages.get("console.status.warn");
            // Not "console.status.off": YAML 1.1 parses a bare off/on/yes/no key as a
            // boolean, so that key would never resolve.
            case OFF -> messages.get("console.status.disabled");
        };
    }

    /** Right-pads so the detail column lines up without needing a monospace assumption. */
    private static String pad(String label) {
        StringBuilder padded = new StringBuilder(label);
        while (padded.length() < 14) {
            padded.append(' ');
        }
        return padded.toString();
    }

    private static int countProblems(List<Entry> entries) {
        int problems = 0;
        for (Entry entry : entries) {
            if (entry.status() != Status.OK) {
                problems++;
            }
        }
        return problems;
    }

    /** Convenience for building the list in order without repeating the boilerplate. */
    public static List<Entry> entries() {
        return new ArrayList<>();
    }

    /** Used by the banner's own tag list; kept here so callers do not import Adventure. */
    public static TagResolver placeholder(String key, String value) {
        return Placeholder.unparsed(key, value);
    }
}
