package dev.lk.gardenshop.sell;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * What happened when a player tried to sell.
 *
 * <p>Every failure mode is named rather than collapsed into a boolean, because each one
 * needs a different message: "you have nothing to sell" and "the economy plugin
 * rejected the payout, your crops are back in your bag" are not the same news.
 */
public record SellResult(
        Outcome outcome,
        List<SellLine> lines,
        BigDecimal total,
        int itemCount,
        int skippedFavorites,
        int leftOverLimit,
        boolean capped,
        String error
) {

    public enum Outcome {
        /** Items were taken and the money was credited. */
        SOLD,
        /** Nothing in the inventory (or hand) was a sellable harvest. */
        NOTHING_TO_SELL,
        /** Everything sellable was marked favorite. */
        ALL_FAVORITED,
        /** No usable economy provider, so nothing was touched. */
        ECONOMY_UNAVAILABLE,
        /** The economy plugin refused the deposit; items were returned. */
        DEPOSIT_FAILED,
        /** The inventory changed mid-sale; nothing was taken. */
        INVENTORY_CHANGED,
        /** The player's sell cooldown has not elapsed. */
        ON_COOLDOWN,
        /**
         * MythicMobs does not have the crops this plugin prices, so nothing was touched.
         *
         * <p>Named rather than reported as "nothing to sell", which is what it would otherwise look
         * like: without the pack every item in the world fails to match, and a player told there is
         * nothing sellable in a bag full of crops has no way to know the server is misconfigured.
         */
        CROP_PACK_MISSING
    }

    public SellResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(error, "error");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    public boolean isSold() {
        return outcome == Outcome.SOLD;
    }

    public static SellResult sold(List<SellLine> lines, BigDecimal total, int itemCount,
                                 int skippedFavorites, int leftOverLimit, boolean capped) {
        return new SellResult(Outcome.SOLD, lines, total, itemCount,
                skippedFavorites, leftOverLimit, capped, "");
    }

    public static SellResult failure(Outcome outcome, String error) {
        return new SellResult(outcome, List.of(), BigDecimal.ZERO, 0, 0, 0, false, error);
    }

    /** @param reason the integrity report's summary, so the message can say what is actually wrong */
    public static SellResult cropPackMissing(String reason) {
        return failure(Outcome.CROP_PACK_MISSING, reason);
    }

    public static SellResult nothingToSell(int skippedFavorites) {
        Outcome outcome = skippedFavorites > 0 ? Outcome.ALL_FAVORITED : Outcome.NOTHING_TO_SELL;
        return new SellResult(outcome, List.of(), BigDecimal.ZERO, 0, skippedFavorites, 0, false, "");
    }
}
