package dev.lk.gardenshop.core.config;

/**
 * Sell-flow limits from {@code config.yml → selling}.
 *
 * @param cooldownSeconds    minimum gap between sells per player; 0 disables
 * @param maxItemsPerBulkSale cap on items consumed by one {@code /gs sell all}
 * @param skipFavorites      honour the favorite flag during bulk sales. Disabling
 *                           this removes the only safety net players have against
 *                           accidentally selling a record harvest
 */
public record SellingSettings(
        int cooldownSeconds,
        int maxItemsPerBulkSale,
        boolean skipFavorites
) {

    public SellingSettings {
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldown-seconds must be >= 0, got " + cooldownSeconds);
        }
        if (maxItemsPerBulkSale <= 0) {
            throw new IllegalArgumentException("max-items-per-bulk-sale must be > 0, got " + maxItemsPerBulkSale);
        }
    }

    public static SellingSettings defaults() {
        return new SellingSettings(0, 2304, true);
    }
}
