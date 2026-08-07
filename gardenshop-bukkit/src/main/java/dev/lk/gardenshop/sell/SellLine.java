package dev.lk.gardenshop.sell;

import dev.lk.gardenshop.core.domain.PriceQuote;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One priced stack in a sale or appraisal.
 *
 * @param slot      the inventory slot it came from, or -1 for a pure appraisal
 * @param amount    how many items this line covers
 * @param lineTotal {@code unitPrice x amount}, precomputed so the message layer never
 *                  redoes the arithmetic and risks disagreeing with the payout
 */
public record SellLine(int slot, int amount, PriceQuote quote, BigDecimal lineTotal) {

    public SellLine {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(lineTotal, "lineTotal");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, got " + amount);
        }
    }

    public static SellLine of(int slot, int amount, PriceQuote quote) {
        return new SellLine(slot, amount, quote, quote.total(amount));
    }

    public String speciesId() {
        return quote.drop().species().id();
    }

    public String displayName() {
        return quote.drop().species().displayName();
    }

    public double weightKg() {
        return quote.drop().weightKg();
    }
}
