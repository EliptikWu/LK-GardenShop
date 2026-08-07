package dev.lk.gardenshop.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Everything wrong (or merely suspicious) about a configuration load.
 *
 * <p>Problems are collected rather than thrown so a single {@code /gs reload}
 * reports every mistake at once, and so the previous good configuration can be
 * kept when any error is present.
 */
public record ValidationReport(List<Issue> issues) {

    public enum Severity {
        /** The configuration cannot be used; the previous snapshot stays live. */
        ERROR,
        /** Usable, but something looks wrong and was defaulted or ignored. */
        WARNING
    }

    /**
     * @param file    which YAML the problem is in, e.g. {@code pricing.yml}
     * @param path    the config path, e.g. {@code weight-bands.bands[2].max}
     * @param message what is wrong, phrased so the owner can act on it
     */
    public record Issue(Severity severity, String file, String path, String message) {
        public Issue {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(message, "message");
        }

        @Override
        public String toString() {
            return path.isEmpty()
                    ? file + ": " + message
                    : file + " → " + path + ": " + message;
        }
    }

    public ValidationReport {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public static ValidationReport empty() {
        return new ValidationReport(List.of());
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
    }

    public boolean isClean() {
        return issues.isEmpty();
    }

    public List<Issue> errors() {
        return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).toList();
    }

    public List<Issue> warnings() {
        return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).toList();
    }

    public ValidationReport merge(ValidationReport other) {
        List<Issue> combined = new ArrayList<>(issues);
        combined.addAll(other.issues());
        return new ValidationReport(combined);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates issues while a loader walks the YAML. Not thread-safe by design. */
    public static final class Builder {

        private final List<Issue> issues = new ArrayList<>();

        public Builder error(String file, String path, String message) {
            issues.add(new Issue(Severity.ERROR, file, path, message));
            return this;
        }

        public Builder warning(String file, String path, String message) {
            issues.add(new Issue(Severity.WARNING, file, path, message));
            return this;
        }

        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        }

        public ValidationReport build() {
            return new ValidationReport(issues);
        }
    }
}
