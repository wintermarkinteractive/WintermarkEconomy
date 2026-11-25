package io.github.wintermarkinteractive.economy.api;

import java.util.Locale;

/**
 * Types shown in the transaction history.
 * <p>
 * Plugins using {@link EconomyAPI} can pick the type by starting the {@code reason} with it,
 * e.g. {@code "BOUNTY: claimed Steve"} or {@code "AUCTION: sold 16x Diamond"}.
 * Without a known prefix the type is {@link #API}.
 */
public enum TransactionType {
    PAY, ADMIN, NOTE, BOUNTY, AUCTION, API, VAULT;

    /** Splits "BOUNTY: text" into a type and the note text. */
    public static Parsed parse(String reason) {
        if (reason == null || reason.isBlank()) return new Parsed(API, "");
        String whole = reason.trim().toUpperCase(Locale.ROOT);
        for (TransactionType t : values()) {
            if (t.name().equals(whole)) return new Parsed(t, "");
        }
        int colon = reason.indexOf(':');
        if (colon > 0) {
            String head = reason.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            for (TransactionType t : values()) {
                if (t.name().equals(head)) return new Parsed(t, reason.substring(colon + 1).trim());
            }
        }
        return new Parsed(API, reason.trim());
    }

    public record Parsed(TransactionType type, String note) { }
}
