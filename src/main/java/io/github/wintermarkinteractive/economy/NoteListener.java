package io.github.wintermarkinteractive.economy;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import static io.github.wintermarkinteractive.economy.Messages.text;

/** Right-click a banknote to deposit it; sneak + right-click deposits every note in the inventory. */
public final class NoteListener implements Listener {

    private final EconomyPlugin plugin;

    public NoteListener(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        ItemStack item = event.getItem();
        NoteManager notes = plugin.notes();
        if (notes.noteId(item) == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        plugin.accounts().ensure(player.getUniqueId(), player.getName());
        PlayerInventory inv = player.getInventory();

        long total = 0;
        int valid = 0;
        int invalid = 0;
        boolean maxBalance = false;

        if (player.isSneaking()) {
            ItemStack[] contents = inv.getStorageContents();
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack it = contents[slot];
                if (notes.noteId(it) == null) continue;
                NoteManager.Redeem r = notes.redeemStack(player, it);
                if (r.maxBalance()) {
                    maxBalance = true;
                    break;
                }
                total += r.total();
                valid += r.valid();
                invalid += r.invalid();
                inv.setItem(slot, null);
            }
            ItemStack off = inv.getItemInOffHand();
            if (!maxBalance && notes.noteId(off) != null) {
                NoteManager.Redeem r = notes.redeemStack(player, off);
                if (r.maxBalance()) {
                    maxBalance = true;
                } else {
                    total += r.total();
                    valid += r.valid();
                    invalid += r.invalid();
                    inv.setItemInOffHand(null);
                }
            }
        } else {
            NoteManager.Redeem r = notes.redeemStack(player, item);
            if (r.maxBalance()) {
                maxBalance = true;
            } else {
                total = r.total();
                valid = r.valid();
                invalid = r.invalid();
                inv.setItem(hand, null);
            }
        }

        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();
        long balance = plugin.accounts().balance(player.getUniqueId());
        if (valid == 1) {
            msg.send(player, "note-redeemed", text("amount", fmt.format(total)), text("balance", fmt.format(balance)));
        } else if (valid > 1) {
            msg.send(player, "note-redeemed-multi", text("amount", fmt.format(total)),
                    text("count", String.valueOf(valid)), text("balance", fmt.format(balance)));
        }
        if (valid > 0) player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        if (invalid > 0) msg.send(player, "note-invalid", text("count", String.valueOf(invalid)));
        if (maxBalance) {
            msg.send(player, "max-balance", text("player", player.getName()),
                    text("max", fmt.format(plugin.accounts().maxBalance())));
        }
    }

    /** Banknotes are paper: don't let them be crafted into books or maps by accident. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack it : event.getInventory().getMatrix()) {
            if (plugin.notes().noteId(it) != null) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
