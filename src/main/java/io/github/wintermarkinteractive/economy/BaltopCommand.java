package io.github.wintermarkinteractive.economy;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

import static io.github.wintermarkinteractive.economy.Messages.text;

/** /baltop [page] */
public final class BaltopCommand implements BasicCommand {

    private static final int PAGE_SIZE = 10;

    private final EconomyPlugin plugin;

    public BaltopCommand(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages msg = plugin.messages();
        MoneyFormat fmt = plugin.money();
        BaltopCache.Snapshot snap = plugin.baltop().get();

        if (snap.sorted().isEmpty()) {
            msg.send(sender, "baltop-empty");
            return;
        }
        int pages = (snap.sorted().size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                page = 1;
            }
        }
        page = Math.max(1, Math.min(page, pages));

        msg.send(sender, "baltop-header",
                text("page", String.valueOf(page)),
                text("pages", String.valueOf(pages)),
                text("total", fmt.format(snap.total())),
                text("total_compact", fmt.formatCompact(snap.total())));

        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, snap.sorted().size());
        for (int i = from; i < to; i++) {
            AccountManager.Entry e = snap.sorted().get(i);
            msg.send(sender, "baltop-line",
                    text("rank", String.valueOf(i + 1)),
                    text("player", e.name()),
                    text("amount", fmt.format(e.balance())),
                    text("amount_compact", fmt.formatCompact(e.balance())));
        }

        if (sender instanceof Player p) {
            Integer rank = snap.ranks().get(p.getUniqueId());
            if (rank != null) {
                long bal = plugin.accounts().balance(p.getUniqueId());
                msg.send(sender, "baltop-self",
                        text("rank", String.valueOf(rank)),
                        text("amount", fmt.format(bal)),
                        text("amount_compact", fmt.formatCompact(bal)));
            }
        }
        if (page < pages) {
            msg.send(sender, "baltop-next", text("next", String.valueOf(page + 1)),
                    Placeholder.styling("next_click", ClickEvent.runCommand("/baltop " + (page + 1))));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return List.of();
    }

    @Override
    public String permission() {
        return "wintermark.eco.baltop";
    }
}
