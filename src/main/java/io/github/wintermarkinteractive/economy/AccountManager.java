package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.EconomyResult.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps every account in memory (fast, works for offline players) and writes each change
 * to SQLite in the background. All balance changes go through one lock, so payments
 * from different threads can never create or lose money.
 */
public final class AccountManager {

    public static final class Account {
        private final UUID id;
        private volatile String name;
        private long balance;
        private final long created;
        private volatile boolean payDisabled;

        Account(UUID id, String name, long balance, long created, boolean payDisabled) {
            this.id = id;
            this.name = name;
            this.balance = balance;
            this.created = created;
            this.payDisabled = payDisabled;
        }

        public UUID id() { return id; }
        public String name() { return name; }
        public boolean payDisabled() { return payDisabled; }
    }

    /** Result of a change: status + balance afterwards (in minor units). */
    public record Change(Status status, long balance) {
        public boolean ok() { return status == Status.SUCCESS; }
    }

    /** One line for /baltop. */
    public record Entry(UUID id, String name, long balance) { }

    private final Database db;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    private volatile long startingBalance;
    private volatile long maxBalance;

    public AccountManager(Database db) {
        this.db = db;
    }

    public void setLimits(long startingBalance, long maxBalance) {
        this.startingBalance = Math.max(0, startingBalance);
        this.maxBalance = maxBalance <= 0 ? Long.MAX_VALUE : maxBalance;
    }

    public long startingBalance() { return startingBalance; }
    public long maxBalance() { return maxBalance; }

    public void load(List<Database.Row> rows) {
        Map<String, Long> nameUpdated = new java.util.HashMap<>();
        for (Database.Row r : rows) {
            accounts.put(r.id(), new Account(r.id(), r.name(), r.balance(), r.created(), r.payDisabled()));
            String key = r.name().toLowerCase(Locale.ROOT);
            Long prev = nameUpdated.get(key);
            if (prev == null || r.updated() > prev) {
                nameUpdated.put(key, r.updated());
                byName.put(key, r.id());
            }
        }
    }

    public int size() { return accounts.size(); }

    public Account get(UUID id) { return accounts.get(id); }

    public Account findByName(String name) {
        if (name == null) return null;
        UUID id = byName.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : accounts.get(id);
    }

    /** Creates the account with the starting balance, or updates the stored name. Returns true if new. */
    public boolean ensure(UUID id, String name) {
        synchronized (lock) {
            Account acc = accounts.get(id);
            if (acc == null) {
                acc = new Account(id, name, startingBalance, System.currentTimeMillis(), false);
                accounts.put(id, acc);
                byName.put(name.toLowerCase(Locale.ROOT), id);
                save(acc);
                return true;
            }
            if (!acc.name.equals(name)) {
                byName.remove(acc.name.toLowerCase(Locale.ROOT), id);
                acc.name = name;
                save(acc);
            }
            byName.put(name.toLowerCase(Locale.ROOT), id);
            return false;
        }
    }

    public long balance(UUID id) {
        synchronized (lock) {
            Account acc = accounts.get(id);
            return acc == null ? 0 : acc.balance;
        }
    }

    public Change deposit(UUID id, long amount) {
        if (amount <= 0) return new Change(Status.INVALID_AMOUNT, balance(id));
        synchronized (lock) {
            Account acc = accounts.get(id);
            if (acc == null) return new Change(Status.NO_ACCOUNT, 0);
            long next;
            try {
                next = Math.addExact(acc.balance, amount);
            } catch (ArithmeticException e) {
                return new Change(Status.MAX_BALANCE, acc.balance);
            }
            if (next > maxBalance) return new Change(Status.MAX_BALANCE, acc.balance);
            acc.balance = next;
            save(acc);
            return new Change(Status.SUCCESS, next);
        }
    }

    public Change withdraw(UUID id, long amount) {
        if (amount <= 0) return new Change(Status.INVALID_AMOUNT, balance(id));
        synchronized (lock) {
            Account acc = accounts.get(id);
            if (acc == null) return new Change(Status.NO_ACCOUNT, 0);
            if (acc.balance < amount) return new Change(Status.INSUFFICIENT_FUNDS, acc.balance);
            acc.balance -= amount;
            save(acc);
            return new Change(Status.SUCCESS, acc.balance);
        }
    }

    public Change set(UUID id, long amount) {
        if (amount < 0) return new Change(Status.INVALID_AMOUNT, balance(id));
        synchronized (lock) {
            Account acc = accounts.get(id);
            if (acc == null) return new Change(Status.NO_ACCOUNT, 0);
            if (amount > maxBalance) return new Change(Status.MAX_BALANCE, acc.balance);
            acc.balance = amount;
            save(acc);
            return new Change(Status.SUCCESS, amount);
        }
    }

    /** Moves money in one step. The returned balance is the sender's. */
    public Change transfer(UUID from, UUID to, long amount) {
        if (amount <= 0 || from.equals(to)) return new Change(Status.INVALID_AMOUNT, balance(from));
        synchronized (lock) {
            Account a = accounts.get(from);
            Account b = accounts.get(to);
            if (a == null || b == null) return new Change(Status.NO_ACCOUNT, a == null ? 0 : a.balance);
            if (a.balance < amount) return new Change(Status.INSUFFICIENT_FUNDS, a.balance);
            long next;
            try {
                next = Math.addExact(b.balance, amount);
            } catch (ArithmeticException e) {
                return new Change(Status.MAX_BALANCE, a.balance);
            }
            if (next > maxBalance) return new Change(Status.MAX_BALANCE, a.balance);
            a.balance -= amount;
            b.balance = next;
            save(a);
            save(b);
            return new Change(Status.SUCCESS, a.balance);
        }
    }

    /** Turns receiving /pay on or off. */
    public void setPayDisabled(UUID id, boolean disabled) {
        synchronized (lock) {
            Account acc = accounts.get(id);
            if (acc == null) return;
            acc.payDisabled = disabled;
            save(acc);
        }
    }

    /** Copy of all balances, for /baltop (sorted by the caller). */
    public List<Entry> snapshot() {
        synchronized (lock) {
            List<Entry> list = new ArrayList<>(accounts.size());
            for (Account a : accounts.values()) list.add(new Entry(a.id, a.name, a.balance));
            return list;
        }
    }

    private void save(Account acc) {
        db.save(acc.id, acc.name, acc.balance, acc.created, acc.payDisabled);
    }
}
