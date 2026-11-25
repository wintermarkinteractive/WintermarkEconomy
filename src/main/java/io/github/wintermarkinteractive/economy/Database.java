package io.github.wintermarkinteractive.economy;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage. Every query runs on one background thread, in order,
 * so the main thread never waits for the disk.
 */
public final class Database {

    public record Row(UUID id, String name, long balance, long created, long updated, boolean payDisabled) { }

    public record TxRow(long id, long time, String type, UUID fromId, String fromName,
                        UUID toId, String toName, long amount, String note) { }

    public record HistoryPage(List<TxRow> rows, int total) { }

    private final File file;
    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WintermarkEconomy-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public Database(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Opens the file, creates/updates tables and converts amounts if the decimals setting changed. */
    public void open(int decimals) throws Exception {
        executor.submit(() -> {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS accounts ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "name TEXT NOT NULL, "
                        + "balance INTEGER NOT NULL, "
                        + "created INTEGER NOT NULL, "
                        + "updated INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS transactions ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "time INTEGER NOT NULL, "
                        + "type TEXT NOT NULL, "
                        + "from_uuid TEXT, from_name TEXT, "
                        + "to_uuid TEXT, to_name TEXT, "
                        + "amount INTEGER NOT NULL, "
                        + "note TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS tx_from ON transactions(from_uuid)");
                st.execute("CREATE INDEX IF NOT EXISTS tx_to ON transactions(to_uuid)");
                st.execute("CREATE INDEX IF NOT EXISTS tx_time ON transactions(time)");
                st.execute("CREATE TABLE IF NOT EXISTS notes ("
                        + "id TEXT PRIMARY KEY, "
                        + "amount INTEGER NOT NULL, "
                        + "creator_uuid TEXT NOT NULL, creator_name TEXT NOT NULL, "
                        + "created INTEGER NOT NULL, "
                        + "redeemed_uuid TEXT, redeemed_name TEXT, redeemed_at INTEGER)");
            }
            addColumnIfMissing("accounts", "pay_disabled", "INTEGER NOT NULL DEFAULT 0");
            migrateDecimals(decimals);
            return null;
        }).get();
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void migrateDecimals(int decimals) throws SQLException {
        String stored = getMeta("decimals");
        if (stored != null) {
            int old;
            try {
                old = Integer.parseInt(stored);
            } catch (NumberFormatException e) {
                old = decimals;
            }
            if (old != decimals) {
                long factor = (long) Math.pow(10, Math.abs(decimals - old));
                String op = decimals > old ? " * " : " / ";
                try (Statement st = connection.createStatement()) {
                    int n = st.executeUpdate("UPDATE accounts SET balance = balance" + op + factor);
                    st.executeUpdate("UPDATE transactions SET amount = amount" + op + factor);
                    st.executeUpdate("UPDATE notes SET amount = amount" + op + factor);
                    log.warning("currency.decimals changed from " + old + " to " + decimals
                            + ": converted " + n + " balances, history and banknotes.");
                }
            }
        }
        setMeta("decimals", String.valueOf(decimals));
    }

    private String getMeta(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ---------- accounts ----------

    /** Reads every account (used once on startup). */
    public List<Row> loadAll() throws Exception {
        return executor.submit(() -> {
            List<Row> rows = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT uuid, name, balance, created, updated, pay_disabled FROM accounts")) {
                while (rs.next()) {
                    try {
                        rows.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2),
                                rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getInt(6) != 0));
                    } catch (IllegalArgumentException bad) {
                        log.warning("Skipping account with invalid UUID: " + rs.getString(1));
                    }
                }
            }
            return rows;
        }).get();
    }

    /** Saves one account in the background. */
    public void save(UUID id, String name, long balance, long created, boolean payDisabled) {
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO accounts(uuid, name, balance, created, updated, pay_disabled) VALUES(?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, balance = excluded.balance, "
                            + "updated = excluded.updated, pay_disabled = excluded.pay_disabled")) {
                ps.setString(1, id.toString());
                ps.setString(2, name);
                ps.setLong(3, balance);
                ps.setLong(4, created);
                ps.setLong(5, now);
                ps.setInt(6, payDisabled ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not save account " + id + " (" + name + ")", e);
            }
        });
    }

    // ---------- transactions ----------

    public void logTransaction(String type, UUID fromId, String fromName, UUID toId, String toName,
                               long amount, String note) {
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO transactions(time, type, from_uuid, from_name, to_uuid, to_name, amount, note) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, now);
                ps.setString(2, type);
                setUuid(ps, 3, fromId);
                ps.setString(4, fromName);
                setUuid(ps, 5, toId);
                ps.setString(6, toName);
                ps.setLong(7, amount);
                ps.setString(8, note);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Could not write transaction history", e);
            }
        });
    }

    private static void setUuid(PreparedStatement ps, int index, UUID id) throws SQLException {
        if (id == null) ps.setNull(index, Types.VARCHAR);
        else ps.setString(index, id.toString());
    }

    /** One page of a player's history, newest first. */
    public CompletableFuture<HistoryPage> history(UUID player, int offset, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String id = player.toString();
            List<TxRow> rows = new ArrayList<>();
            int total = 0;
            try {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM transactions WHERE from_uuid = ? OR to_uuid = ?")) {
                    ps.setString(1, id);
                    ps.setString(2, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) total = rs.getInt(1);
                    }
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT id, time, type, from_uuid, from_name, to_uuid, to_name, amount, note "
                                + "FROM transactions WHERE from_uuid = ? OR to_uuid = ? "
                                + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                    ps.setString(1, id);
                    ps.setString(2, id);
                    ps.setInt(3, limit);
                    ps.setInt(4, offset);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new TxRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                                    parseUuid(rs.getString(4)), rs.getString(5),
                                    parseUuid(rs.getString(6)), rs.getString(7),
                                    rs.getLong(8), rs.getString(9)));
                        }
                    }
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "Could not read transaction history", e);
            }
            return new HistoryPage(rows, total);
        }, executor);
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Deletes history and redeemed banknotes older than the given number of days (0 = keep forever). */
    public void cleanup(int keepDays) {
        if (keepDays <= 0) return;
        final long cutoff = System.currentTimeMillis() - keepDays * 86_400_000L;
        executor.execute(() -> {
            try (PreparedStatement a = connection.prepareStatement("DELETE FROM transactions WHERE time < ?");
                 PreparedStatement b = connection.prepareStatement(
                         "DELETE FROM notes WHERE redeemed_at IS NOT NULL AND redeemed_at < ?")) {
                a.setLong(1, cutoff);
                b.setLong(1, cutoff);
                int tx = a.executeUpdate();
                int notes = b.executeUpdate();
                if (tx + notes > 0) {
                    log.info("Cleanup: removed " + tx + " old history entries and " + notes + " old redeemed banknotes.");
                }
            } catch (SQLException e) {
                log.log(Level.WARNING, "History cleanup failed", e);
            }
        });
    }

    // ---------- banknotes ----------

    /** Id -> amount of every banknote that has not been redeemed yet (used once on startup). */
    public Map<String, Long> loadOpenNotes() throws Exception {
        return executor.submit(() -> {
            Map<String, Long> map = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, amount FROM notes WHERE redeemed_at IS NULL")) {
                while (rs.next()) map.put(rs.getString(1), rs.getLong(2));
            }
            return map;
        }).get();
    }

    public void insertNote(String id, long amount, UUID creator, String creatorName) {
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO notes(id, amount, creator_uuid, creator_name, created) VALUES(?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setLong(2, amount);
                ps.setString(3, creator.toString());
                ps.setString(4, creatorName);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not save banknote " + id, e);
            }
        });
    }

    public void markNoteRedeemed(String id, UUID by, String byName) {
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE notes SET redeemed_uuid = ?, redeemed_name = ?, redeemed_at = ? WHERE id = ?")) {
                ps.setString(1, by.toString());
                ps.setString(2, byName);
                ps.setLong(3, now);
                ps.setString(4, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Could not mark banknote " + id + " as redeemed", e);
            }
        });
    }

    // ---------- shutdown ----------

    /** Waits for pending writes, then closes the file. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log.severe("Database writes did not finish within 15 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Could not close the database", e);
        }
    }
}
