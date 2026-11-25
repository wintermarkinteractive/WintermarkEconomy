package io.github.wintermarkinteractive.economy;

import io.github.wintermarkinteractive.economy.api.TransactionType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.wintermarkinteractive.economy.Messages.text;

/**
 * Banknotes. Every note has a random id; the id and its value live in the database.
 * Redeeming removes the id, so a copied (duped) note is worth nothing. The value is read
 * from the database, never from the item, so edited items can't print money either.
 */
public final class NoteManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final EconomyPlugin plugin;
    private final NamespacedKey idKey;
    private final Map<String, Long> open = new ConcurrentHashMap<>();

    public NoteManager(EconomyPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "note_id");
    }

    public void load(Map<String, Long> openNotes) {
        open.putAll(openNotes);
    }

    public int openCount() { return open.size(); }

    /** Note id stored on the item, or null if it isn't a banknote. */
    public String noteId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /** Creates a new note worth {@code amount} (money must already be taken). */
    public ItemStack create(Player signer, long amount) {
        String id = UUID.randomUUID().toString().replace("-", "");
        open.put(id, amount);
        plugin.database().insertNote(id, amount, signer.getUniqueId(), signer.getName());
        return buildItem(id, amount, signer.getName());
    }

    /**
     * Takes the note's value out of the open list. Returns the value, or null when the note
     * was already redeemed / never existed. Only one caller can ever get the value of an id.
     */
    public Long claim(String id) {
        return id == null ? null : open.remove(id);
    }

    /** Puts a claimed note back (when the deposit failed, e.g. max balance). */
    public void unclaim(String id, long amount) {
        open.put(id, amount);
    }

    public void markRedeemed(String id, Player by) {
        plugin.database().markNoteRedeemed(id, by.getUniqueId(), by.getName());
    }

    private ItemStack buildItem(String id, long amount, String signer) {
        FileConfiguration cfg = plugin.getConfig();
        Material mat = Material.matchMaterial(cfg.getString("banknotes.material", "PAPER"));
        if (mat == null || !mat.isItem() || mat.isAir()) mat = Material.PAPER;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        MoneyFormat fmt = plugin.money();
        String date = new SimpleDateFormat(cfg.getString("banknotes.date-format", "yyyy-MM-dd")).format(new Date());
        TagResolver tags = TagResolver.resolver(
                text("amount", fmt.format(amount)),
                text("amount_compact", fmt.formatCompact(amount)),
                text("signer", signer),
                text("date", date),
                text("id", id.substring(0, 8)));

        meta.displayName(noItalic(MM.deserialize(cfg.getString("banknotes.name", "<gold>Banknote <amount>"), tags)));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("banknotes.lore")) lore.add(noItalic(MM.deserialize(line, tags)));
        meta.lore(lore);
        if (cfg.getBoolean("banknotes.glow", true)) meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ---------- redeem flow (main thread) ----------

    /** Result of redeeming items. */
    public record Redeem(long total, int valid, int invalid, boolean maxBalance) { }

    /**
     * Redeems one item stack (all copies in it). Copies beyond the first are invalid.
     * The caller removes the item from the inventory afterwards unless {@code keep} is set.
     */
    public Redeem redeemStack(Player player, ItemStack item) {
        String id = noteId(item);
        if (id == null) return new Redeem(0, 0, 0, false);
        Long value = claim(id);
        int copies = item.getAmount();
        if (value == null) {
            plugin.getLogger().warning(player.getName() + " tried to redeem an invalid banknote #"
                    + id.substring(0, Math.min(8, id.length())) + " x" + copies + " (copied or already used).");
            return new Redeem(0, 0, copies, false);
        }
        AccountManager.Change c = plugin.accounts().deposit(player.getUniqueId(), value);
        if (!c.ok()) {
            unclaim(id, value);
            return new Redeem(0, 0, 0, true);
        }
        markRedeemed(id, player);
        plugin.database().logTransaction(TransactionType.NOTE.name(), null, "Banknote",
                player.getUniqueId(), player.getName(), value, "redeemed #" + id.substring(0, 8));
        if (copies > 1) {
            plugin.getLogger().warning(player.getName() + " had " + copies + " copies of banknote #"
                    + id.substring(0, 8) + " - only one was paid out.");
        }
        return new Redeem(value, 1, copies - 1, false);
    }
}
