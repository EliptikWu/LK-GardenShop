package dev.lk.gardenshop.core.config;

import java.util.Objects;

/**
 * Economy configuration from {@code config.yml → economy}.
 *
 * @param preference which backend the owner wants
 * @param currency   which currency to pay in, or blank for the backend's default.
 *                   Only VaultUnlocked can honour this: classic Vault has no concept
 *                   of multiple currencies, so a non-blank value is reported as
 *                   ignored rather than silently dropped
 */
public record EconomySettings(EconomyPreference preference, String currency) {

    public EconomySettings {
        Objects.requireNonNull(preference, "preference");
        currency = currency == null ? "" : currency.trim();
    }

    public static EconomySettings defaults() {
        return new EconomySettings(EconomyPreference.AUTO, "");
    }

    /** Whether a specific currency was asked for. */
    public boolean hasCurrency() {
        return !currency.isEmpty();
    }
}
