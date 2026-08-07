package dev.lk.gardenshop.economy;

import dev.lk.gardenshop.core.config.EconomyPreference;
import dev.lk.gardenshop.core.config.EconomySettings;
import org.bukkit.Server;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Chooses which {@link EconomyProvider} is in charge, and re-chooses on reload.
 *
 * <p>Re-resolving matters more than it looks: economy plugins register with Vault at
 * their own pace, and a server that starts CMI or CoinsEngine after this plugin would
 * otherwise be stuck on a NoOp until the next restart. {@code /gs reload} fixes it.
 *
 * <p>Under {@link EconomyPreference#AUTO} the order is VaultUnlocked, then classic Vault,
 * then nothing. VaultUnlocked goes first because it carries money as {@link java.math.BigDecimal}
 * and supports named currencies, where classic Vault is {@code double}-only and
 * single-currency — same economy plugins behind it, fewer sharp edges in front.
 */
public final class EconomyRouter {

    private final Server server;
    private final Logger logger;
    private final AtomicReference<EconomyProvider> active =
            new AtomicReference<>(new NoOpEconomyProvider("not resolved yet"));

    public EconomyRouter(Server server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    /** The live provider. Never {@code null}. */
    public EconomyProvider provider() {
        return active.get();
    }

    /**
     * Picks a provider for the given settings and publishes it.
     *
     * @return the provider now in use
     */
    public EconomyProvider resolve(EconomySettings settings) {
        EconomyProvider resolved = switch (settings.preference()) {
            case NONE -> new NoOpEconomyProvider("economy.provider is set to NONE");

            case VAULT -> {
                warnIfCurrencyIgnored(settings);
                yield vault().orElseGet(() -> new NoOpEconomyProvider(
                        "economy.provider is set to VAULT but no Vault economy is available"));
            }

            case VAULT_UNLOCKED -> vaultUnlocked(settings).orElseGet(() -> new NoOpEconomyProvider(
                    "economy.provider is set to VAULT_UNLOCKED but no Vault2 economy is available"));

            case AUTO -> vaultUnlocked(settings)
                    .or(() -> {
                        warnIfCurrencyIgnored(settings);
                        return vault();
                    })
                    .orElseGet(() -> new NoOpEconomyProvider("no supported economy plugin was found"));
        };

        active.set(resolved);
        if (resolved.isAvailable()) {
            logger.info("Payouts will go through " + resolved.description() + ".");
        } else {
            logger.warning("Selling is disabled: " + resolved.description()
                    + ". Install Vault (or VaultUnlocked) plus an economy plugin, then run /gs reload.");
        }
        return resolved;
    }

    private Optional<EconomyProvider> vaultUnlocked(EconomySettings settings) {
        if (!VaultUnlockedEconomyProvider.isApiPresent()) {
            return Optional.empty();
        }
        return VaultUnlockedEconomyProvider.hook(server, logger, settings.currency())
                .map(EconomyProvider.class::cast);
    }

    private Optional<EconomyProvider> vault() {
        return VaultEconomyProvider.hook(server, logger).map(EconomyProvider.class::cast);
    }

    /**
     * Classic Vault has no notion of currencies, so an owner who configured one is told
     * it is being ignored rather than left wondering why nothing changed.
     */
    private void warnIfCurrencyIgnored(EconomySettings settings) {
        if (settings.hasCurrency()) {
            logger.warning("economy.currency is set to '" + settings.currency()
                    + "' but classic Vault does not support named currencies, so it is ignored. "
                    + "Install VaultUnlocked to use it.");
        }
    }
}
