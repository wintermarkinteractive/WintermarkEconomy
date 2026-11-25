package io.github.wintermarkinteractive.economy;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sorted copy of all balances, rebuilt on a timer off the main thread. */
public final class BaltopCache {

    public record Snapshot(List<AccountManager.Entry> sorted, Map<UUID, Integer> ranks, long total, long builtAt) { }

    private final AccountManager accounts;
    private volatile Snapshot current = new Snapshot(List.of(), Map.of(), 0, 0);

    public BaltopCache(AccountManager accounts) {
        this.accounts = accounts;
    }

    public Snapshot get() {
        return current;
    }

    public void refresh() {
        List<AccountManager.Entry> list = accounts.snapshot();
        list.sort(Comparator.comparingLong(AccountManager.Entry::balance).reversed()
                .thenComparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
        Map<UUID, Integer> ranks = new HashMap<>(list.size() * 2);
        long total = 0;
        for (int i = 0; i < list.size(); i++) {
            AccountManager.Entry e = list.get(i);
            ranks.put(e.id(), i + 1);
            try {
                total = Math.addExact(total, e.balance());
            } catch (ArithmeticException overflow) {
                total = Long.MAX_VALUE;
            }
        }
        current = new Snapshot(List.copyOf(list), ranks, total, System.currentTimeMillis());
    }
}
