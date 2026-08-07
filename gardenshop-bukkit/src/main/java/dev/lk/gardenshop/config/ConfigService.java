package dev.lk.gardenshop.config;

import dev.lk.gardenshop.core.ConfigSnapshot;
import dev.lk.gardenshop.core.config.ValidationReport;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the live configuration and swaps it atomically.
 *
 * <p>The whole reload story rests on one rule: a new {@link ConfigSnapshot} is built
 * and validated <em>completely</em> before it is published. Readers hold a reference
 * to an immutable snapshot, so they either see the old configuration or the new one
 * — never a half-applied mixture, and never a torn read while an admin is mid-reload.
 *
 * <p>The other half of the rule: a load carrying any error is <b>rejected</b>. A
 * typo in {@code pricing.yml} must not take the shop down, so the previous good
 * snapshot simply stays live and the admin gets a list of what to fix.
 */
public final class ConfigService {

    /** How many issues to echo before truncating, so one bad file cannot flood chat. */
    private static final int MAX_REPORTED_ISSUES = 15;

    private final YamlConfigLoader loader;
    private final Logger logger;
    private final AtomicReference<ConfigSnapshot> live = new AtomicReference<>();

    public ConfigService(YamlConfigLoader loader, Logger logger) {
        this.loader = loader;
        this.logger = logger;
    }

    /**
     * @param applied whether the new snapshot became live
     * @param report  what the load found, for echoing back to the command sender
     */
    public record ReloadOutcome(boolean applied, ValidationReport report) {

        public List<ValidationReport.Issue> reportableIssues() {
            List<ValidationReport.Issue> issues = report.hasErrors() ? report.errors() : report.warnings();
            return issues.size() <= MAX_REPORTED_ISSUES ? issues : issues.subList(0, MAX_REPORTED_ISSUES);
        }

        public int hiddenIssueCount() {
            int total = report.hasErrors() ? report.errors().size() : report.warnings().size();
            return Math.max(0, total - MAX_REPORTED_ISSUES);
        }
    }

    /**
     * First load, during {@code onEnable}.
     *
     * @return {@code false} when nothing usable could be loaded, in which case there
     *         is no configuration at all and the caller should disable the plugin
     */
    public boolean loadInitial() {
        loader.saveDefaults();
        YamlConfigLoader.LoadResult result = loader.load();

        logIssues(result.report());
        if (!result.isUsable()) {
            logger.severe("Configuration could not be loaded — see the errors above. Selling is disabled.");
            return false;
        }

        live.set(result.snapshot());
        logger.info(() -> "Loaded " + result.snapshot().registry().size() + " drop types across "
                + result.snapshot().species().size() + " crops.");
        return true;
    }

    /** Re-reads every file. Keeps the current snapshot untouched unless the new one is clean. */
    public ReloadOutcome reload() {
        YamlConfigLoader.LoadResult result = loader.load();
        logIssues(result.report());

        if (!result.isUsable()) {
            logger.warning("Reload rejected — keeping the previously loaded configuration.");
            return new ReloadOutcome(false, result.report());
        }

        live.set(result.snapshot());
        logger.info(() -> "Reloaded configuration: " + result.snapshot().registry().size()
                + " drop types across " + result.snapshot().species().size() + " crops.");
        return new ReloadOutcome(true, result.report());
    }

    /**
     * The live configuration.
     *
     * @throws IllegalStateException if called before a successful load, which would
     *                               be a wiring bug rather than a config problem
     */
    public ConfigSnapshot snapshot() {
        ConfigSnapshot snapshot = live.get();
        if (snapshot == null) {
            throw new IllegalStateException("configuration accessed before it was loaded");
        }
        return snapshot;
    }

    public boolean isLoaded() {
        return live.get() != null;
    }

    private void logIssues(ValidationReport report) {
        for (ValidationReport.Issue issue : report.errors()) {
            logger.log(Level.SEVERE, issue.toString());
        }
        for (ValidationReport.Issue issue : report.warnings()) {
            logger.log(Level.WARNING, issue.toString());
        }
    }
}
