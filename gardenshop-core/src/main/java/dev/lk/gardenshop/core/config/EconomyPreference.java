package dev.lk.gardenshop.core.config;

import java.util.Locale;
import java.util.Optional;

/** Which economy backend the owner wants, from {@code config.yml → economy.provider}. */
public enum EconomyPreference {

    /** Pick the best available: VaultUnlocked, then Vault, then none. */
    AUTO,

    /** Classic Vault only; refuse to fall back. */
    VAULT,

    /** VaultUnlocked (Vault2) only; refuse to fall back. */
    VAULT_UNLOCKED,

    /** Deliberately no payouts — useful for testing prices without touching balances. */
    NONE;

    public static Optional<EconomyPreference> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (EconomyPreference preference : values()) {
            if (preference.name().equals(normalised)) {
                return Optional.of(preference);
            }
        }
        return Optional.empty();
    }
}
