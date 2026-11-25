package io.github.wintermarkinteractive.economy;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import static io.github.wintermarkinteractive.economy.Messages.text;

/** Shows a page of transaction history (query runs in the background). */
public final class HistoryView {

    private final EconomyPlugin plugin;

    public HistoryView(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param baseCommand command for the "next page" link, e.g. "/money history" or "/eco history Steve"
     */
    public void show(CommandSender viewer, UUID target, String targetName, int page, String baseCommand) {
        int size = Math.max(1, Math.min(20, plugin.getConfig().getInt("history.page-size", 8)));
        int p = Math.max(1, page);
        plugin.database().history(target, (p - 1) * size, size).thenAccept(result ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int pages = Math.max(1, (result.total() + size - 1) / size);
                    if (result.rows().isEmpty() && result.total() > 0 && p > pages) {
                        show(viewer, target, targetName, pages, baseCommand);
                        return;
                    }
                    render(viewer, target, targetName, p, pages, result, baseCommand);
                }));
    }

    private void render(CommandSender viewer, UUID target, String targetName, int page, int pages,
                        Database.HistoryPage result, String baseCommand) {
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();
        if (result.total() == 0) {
            msg.send(viewer, "history-empty", text("player", targetName));
            return;
        }
        msg.send(viewer, "history-header", text("player", targetName),
                text("page", String.valueOf(page)), text("pages", String.valueOf(pages)));

        SimpleDateFormat timeFmt;
        try {
            timeFmt = new SimpleDateFormat(plugin.getConfig().getString("history.time-format", "MM-dd HH:mm"));
        } catch (IllegalArgumentException e) {
            timeFmt = new SimpleDateFormat("MM-dd HH:mm");
        }

        for (Database.TxRow row : result.rows()) {
            boolean incoming = target.equals(row.toId());
            boolean isSet = "ADMIN".equals(row.type()) && row.note() != null && row.note().startsWith("set");
            String other = incoming ? row.fromName() : row.toName();
            if (other == null || other.isEmpty()) other = "Server";
            String key = isSet ? "history-set" : incoming ? "history-in" : "history-out";
            msg.send(viewer, key,
                    text("time", timeFmt.format(new Date(row.time()))),
                    text("amount", fmt.format(row.amount())),
                    text("amount_compact", fmt.formatCompact(row.amount())),
                    text("type", row.type()),
                    text("other", other),
                    text("note", row.note() == null ? "" : row.note()));
        }
        if (page < pages) {
            String cmd = baseCommand + " " + (page + 1);
            msg.send(viewer, "history-next", text("command", cmd),
                    Placeholder.styling("next_click", ClickEvent.runCommand(cmd)));
        }
    }
}
