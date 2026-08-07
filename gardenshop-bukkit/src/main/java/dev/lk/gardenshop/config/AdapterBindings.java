package dev.lk.gardenshop.config;

import dev.lk.gardenshop.core.registry.AdapterSources;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@code adapter-bindings.yml} — which item from another plugin sells as which crop.
 *
 * <p>Its own file, and the only one this plugin ever <b>writes</b>. {@code /gs adapter bind} exists
 * because the two halves of this job belong to different parties: only a person knows that a
 * particular tomato is meant to be an Odre, and only the plugin can get the id and the YAML right.
 *
 * <p>Kept out of {@code crops.yml} on purpose. That file is mostly a long explanation of how crop
 * names are composed, and Bukkit's YAML writer does not round-trip comments faithfully — the first
 * automated write would quietly eat the documentation a server owner relies on. A file the plugin
 * owns can be rewritten freely.
 *
 * <p>Not extracted at startup either. It appears the first time someone binds something, so a server
 * that only grows the Mythic pack never sees it.
 *
 * <h2>Shape</h2>
 * <pre>
 * bindings:
 *   - id: 'ia:mygarden:tomato'
 *     crop: odre
 * </pre>
 * A list of pairs rather than {@code id: crop} because Bukkit treats {@code .} in a key as a path
 * separator, and an adapter id is a foreign string we do not get to constrain.
 */
public final class AdapterBindings {

    public static final String FILE = "adapter-bindings.yml";

    private static final String SECTION = "bindings";
    private static final String ID = "id";
    private static final String CROP = "crop";

    private static final List<String> HEADER = List.of(
            "Written by /gs adapter bind. Safe to edit by hand, and safe to delete.",
            "",
            "Each entry says: an item that the adapter reports under this id sells as this crop's",
            "PLAIN drop -- normal variant, no mutations. Items from other plugins carry no variant",
            "or mutation information, so anything richer would be invented.",
            "",
            "You do not need entries for the Mythic pack. Mythic and Crucible ids are matched",
            "automatically, because the part after the prefix is the Mythic type name.",
            "",
            "Run '/gs adapter hand' while holding an item to see the ids it answers to.");

    private final Plugin plugin;

    public AdapterBindings(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public File file() {
        return new File(plugin.getDataFolder(), FILE);
    }

    public boolean exists() {
        return file().exists();
    }

    /**
     * Every binding, normalised adapter id → crop id.
     *
     * <p>Insertion-ordered so a rewrite does not shuffle the file, and empty — never null — when the
     * file is absent or unreadable. A broken bindings file must not be able to take the shop down:
     * the worst it can cost is the extra ids it declares.
     */
    public Map<String, String> load() {
        return load(problem -> plugin.getLogger().warning(problem));
    }

    /**
     * @param onProblem given a human-readable reason when the file is there but unusable, so a
     *                  reload can put it in front of whoever typed the command rather than only in
     *                  the console they are not reading
     */
    public Map<String, String> load(Consumer<String> onProblem) {
        File file = file();
        if (!file.exists()) {
            return Map.of();
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException e) {
            onProblem.accept(FILE + " could not be read (" + e.getMessage()
                    + "), so no extra item ids are mapped. Fix it or delete it.");
            return Map.of();
        }
        return read(yaml);
    }

    /** Parses an already-loaded document, for the config loader which reads it as part of a set. */
    public static Map<String, String> read(YamlConfiguration yaml) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (Map<?, ?> entry : yaml.getMapList(SECTION)) {
            String id = string(entry.get(ID));
            String crop = string(entry.get(CROP));
            if (id == null || crop == null) {
                continue;
            }
            bindings.put(AdapterSources.normalise(id), crop.trim());
        }
        return bindings;
    }

    /**
     * Adds or replaces one binding and writes the file.
     *
     * @return the crop this id was bound to before, if it was already bound
     * @throws IOException so the caller can tell the sender it failed instead of claiming success
     */
    public Optional<String> bind(String adapterId, String cropId) throws IOException {
        // A mutable copy: load() hands back an immutable map, and the absent-file case hands back
        // Map.of(), so mutating the result in place fails on exactly the first bind a server ever does.
        Map<String, String> bindings = new LinkedHashMap<>(load());
        String previous = bindings.put(AdapterSources.normalise(adapterId), cropId.trim());
        write(bindings);
        return Optional.ofNullable(previous);
    }

    /**
     * Removes a binding and writes the file.
     *
     * @return the crop it was bound to, or empty if there was nothing to remove — in which case
     *         nothing is written
     */
    public Optional<String> unbind(String adapterId) throws IOException {
        Map<String, String> bindings = new LinkedHashMap<>(load());
        String removed = bindings.remove(AdapterSources.normalise(adapterId));
        if (removed == null) {
            return Optional.empty();
        }
        write(bindings);
        return Optional.of(removed);
    }

    private void write(Map<String, String> bindings) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(HEADER);

        List<Map<String, String>> entries = bindings.entrySet().stream()
                .map(binding -> Map.of(ID, binding.getKey(), CROP, binding.getValue()))
                .toList();
        yaml.set(SECTION, entries);

        File target = file();
        File folder = target.getParentFile();
        if (folder != null && !folder.exists() && !folder.mkdirs()) {
            throw new IOException("could not create " + folder);
        }
        yaml.save(target);
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
