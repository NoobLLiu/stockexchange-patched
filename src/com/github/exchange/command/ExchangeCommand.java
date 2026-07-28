package com.github.exchange.command;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.gui.ExchangeGUI;
import com.github.exchange.manager.ItemManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ExchangeCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB_COMMANDS = Arrays.asList(
        "gui",
        "exchange",
        "register",
        "suspend",
        "unsuspend",
        "tax",
        "reload",
        "reconnectdb",
        "announce",
        "announcements"
    );

    private final StockExchangePlugin plugin;

    public ExchangeCommand(StockExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(this.plugin.isStorageAvailable() || args.length > 0 && (
            "reload".equalsIgnoreCase(args[0])
                || "reconnectdb".equalsIgnoreCase(args[0])
                || "tax".equalsIgnoreCase(args[0])
        ))) {
            sender.sendMessage("\u00a74[\u00a76\u00a7lStockExchange\u00a74]\u00a77:\u00a7c\u6570\u636e\u5e93\u8fde\u63a5\u5931\u8d25\uff0c\u529f\u80fd\u6682\u65e0\u6cd5\u4f7f\u7528\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\u5904\u7406\uff01");
            return true;
        }
        if (args.length == 0) {
            this.sendHelp(sender);
            return true;
        }
        String subCmd = args[0].toLowerCase();
        switch (subCmd) {
            case "gui":
                return this.handleGui(sender);
            case "exchange":
                return this.handleExchange(sender, args);
            case "register":
                return this.handleRegister(sender);
            case "suspend":
                return this.handleSuspend(sender, args);
            case "unsuspend":
                return this.handleUnsuspend(sender, args);
            case "tax":
                return this.handleTax(sender, args);
            case "reload":
                return this.handleReload(sender);
            case "reconnectdb":
                return this.handleReconnectDb(sender);
            case "announce":
                return this.handleAnnounce(sender, args);
            case "announcements":
                return this.handleAnnouncements(sender);
            default:
                this.sendHelp(sender);
                return true;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("\u00a76===== StockExchange \u5e2e\u52a9 =====");
        sender.sendMessage("\u00a7e/se gui \u00a77- \u6253\u5f00\u5168\u7403\u5e02\u573a");
        sender.sendMessage("\u00a7e/se exchange <d2m|m2d> \u00a77- \u5728\u94bb\u77f3\u548c" + this.plugin.getCurrencyName() + "\u4e4b\u95f4\u5151\u6362");
        if (sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7e/se register \u00a77- \u6ce8\u518c\u624b\u4e2d\u7269\u54c1\u4e3a\u4e0a\u5e02\u54c1\u79cd");
            sender.sendMessage("\u00a7e/se suspend <id> \u00a77- \u505c\u724c");
            sender.sendMessage("\u00a7e/se unsuspend <id> \u00a77- \u590d\u724c");
            sender.sendMessage("\u00a7e/se tax [0-100] \u00a77- \u67e5\u770b\u6216\u66f4\u6539\u7edf\u4e00\u4ea4\u6613\u7a0e\u7387");
            sender.sendMessage("\u00a7e/se reload \u00a77- \u91cd\u8f7d\u914d\u7f6e");
            sender.sendMessage("\u00a7e/se reconnectdb \u00a77- \u91cd\u65b0\u8fde\u63a5\u6570\u636e\u5e93");
            sender.sendMessage("\u00a7e/se announce add <\u5185\u5bb9> \u00a77- \u65b0\u589e\u516c\u544a");
            sender.sendMessage("\u00a7e/se announce addline <ID> <\u5185\u5bb9> \u00a77- \u516c\u544a\u672b\u5c3e\u8ffd\u52a0\u4e00\u884c");
            sender.sendMessage("\u00a7e/se announce edit <ID> <\u5185\u5bb9> \u00a77- \u7f16\u8f91\u516c\u544a");
            sender.sendMessage("\u00a7e/se announce del <ID> \u00a77- \u5220\u9664\u516c\u544a");
            sender.sendMessage("\u00a7e/se announce delline <ID> \u00a77- \u5220\u9664\u516c\u544a\u6700\u4e0b\u65b9\u4e00\u884c");
            sender.sendMessage("\u00a7e/se announcements \u00a77- \u67e5\u770b\u516c\u544a\u5217\u8868");
        }
    }

    private boolean handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u6253\u5f00GUI\u3002");
            return true;
        }
        ExchangeGUI.openItemList(this.plugin, player);
        return true;
    }

    private boolean handleExchange(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("\u00a7c\u7528\u6cd5: /se exchange <d2m|m2d>");
            return true;
        }
        String mode = args[1].toLowerCase();
        if ("d2m".equals(mode)) {
            sender.sendMessage(this.plugin.exchangeDiamondForMoney(player));
            return true;
        }
        if ("m2d".equals(mode)) {
            sender.sendMessage(this.plugin.exchangeMoneyForDiamond(player));
            return true;
        }
        sender.sendMessage("\u00a7c\u7528\u6cd5: /se exchange <d2m|m2d>");
        return true;
    }

    private boolean handleRegister(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("\u00a7c\u53ea\u6709\u73a9\u5bb6\u53ef\u4ee5\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        if (!sender.hasPermission("exchange.register") && !sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            sender.sendMessage("\u00a7c\u8bf7\u624b\u6301\u8981\u6ce8\u518c\u7684\u7269\u54c1\u3002");
            return true;
        }
        ItemManager.RegisterResult result = this.plugin.getItemManager().registerCatalogItem(player, item);
        sender.sendMessage(result.getMessage());
        return true;
    }

    private boolean handleSuspend(CommandSender sender, String[] args) {
        if (!sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("\u00a7c\u7528\u6cd5: /se suspend <\u54c1\u79cdID>");
            return true;
        }
        try {
            int itemId = Integer.parseInt(args[1]);
            if (this.plugin.getItemManager().getItemStatus(itemId) == null) {
                sender.sendMessage("\u00a7c\u54c1\u79cd\u4e0d\u5b58\u5728\u3002");
                return true;
            }
            this.plugin.getItemManager().getItemStatus(itemId).setSuspended(true);
            this.plugin.getItemManager().updateItemStatus(this.plugin.getItemManager().getItemStatus(itemId));
            sender.sendMessage("\u00a7a\u54c1\u79cd #" + itemId + " \u5df2\u505c\u724c\u3002");
        } catch (NumberFormatException e) {
            sender.sendMessage("\u00a7c\u65e0\u6548\u7684\u54c1\u79cdID\u3002");
        }
        return true;
    }

    private boolean handleUnsuspend(CommandSender sender, String[] args) {
        if (!sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("\u00a7c\u7528\u6cd5: /se unsuspend <\u54c1\u79cdID>");
            return true;
        }
        try {
            int itemId = Integer.parseInt(args[1]);
            if (this.plugin.getItemManager().getItemStatus(itemId) == null) {
                sender.sendMessage("\u00a7c\u54c1\u79cd\u4e0d\u5b58\u5728\u3002");
                return true;
            }
            this.plugin.getItemManager().getItemStatus(itemId).setSuspended(false);
            this.plugin.getItemManager().updateItemStatus(this.plugin.getItemManager().getItemStatus(itemId));
            sender.sendMessage("\u00a7a\u54c1\u79cd #" + itemId + " \u5df2\u590d\u724c\u3002");
        } catch (NumberFormatException e) {
            sender.sendMessage("\u00a7c\u65e0\u6548\u7684\u54c1\u79cdID\u3002");
        }
        return true;
    }

    private boolean handleTax(CommandSender sender, String[] args) {
        if (!sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage("\u00a7e\u5f53\u524d\u7edf\u4e00\u4ea4\u6613\u7a0e\u7387: \u00a7f"
                + this.plugin.getTaxRatePercent().toPlainString() + "%");
            return true;
        }
        try {
            BigDecimal percent = new BigDecimal(args[1]);
            if (!this.plugin.setTaxRatePercent(percent)) {
                sender.sendMessage("\u00a7c\u7a0e\u7387\u5fc5\u987b\u5728 0 \u5230 100 \u4e4b\u95f4\u3002");
                return true;
            }
            sender.sendMessage("\u00a7a\u7edf\u4e00\u4ea4\u6613\u7a0e\u7387\u5df2\u66f4\u6539\u4e3a "
                + this.plugin.getTaxRatePercent().toPlainString() + "%\uff0c\u5df2\u7acb\u5373\u4fdd\u5b58\u3002");
        } catch (NumberFormatException ex) {
            sender.sendMessage("\u00a7c\u7528\u6cd5: /se tax <0-100>");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        this.plugin.loadConfigValues();
        boolean ok = this.plugin.reconnectStorage();
        sender.sendMessage(ok ? "\u00a7a\u914d\u7f6e\u5df2\u91cd\u8f7d\uff0c\u6570\u636e\u5e93\u8fde\u63a5\u5df2\u6062\u590d\u3002" : "\u00a7e\u914d\u7f6e\u5df2\u91cd\u8f7d\uff0c\u4f46\u6570\u636e\u5e93\u91cd\u8fde\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u63a7\u5236\u53f0\u65e5\u5fd7\u3002");
        return true;
    }

    private boolean handleReconnectDb(CommandSender sender) {
        if (!sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        boolean ok = this.plugin.reconnectStorage();
        sender.sendMessage(ok ? "\u00a7a\u6570\u636e\u5e93\u91cd\u8fde\u6210\u529f\uff0c\u529f\u80fd\u5df2\u6062\u590d\u3002" : "\u00a7c\u6570\u636e\u5e93\u91cd\u8fde\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u914d\u7f6e\u4e0e\u63a7\u5236\u53f0\u65e5\u5fd7\u3002");
        return true;
    }

    private boolean handleAnnounce(CommandSender sender, String[] args) {
        if (!sender.hasPermission("exchange.admin")) {
            sender.sendMessage("\u00a7c\u4f60\u6ca1\u6709\u6743\u9650\u6267\u884c\u6b64\u64cd\u4f5c\u3002");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("\u00a7c\u7528\u6cd5: /se announce <add|edit|del> ...");
            return true;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "add": {
                String content = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
                if (content.isEmpty()) {
                    sender.sendMessage("\u00a7c\u7528\u6cd5: /se announce add <\u5185\u5bb9>");
                    return true;
                }
                int id = this.plugin.addAnnouncement(content);
                sender.sendMessage("\u00a7a\u516c\u544a\u5df2\u521b\u5efa\uff0cID: " + id);
                return true;
            }
            case "addline": {
                if (args.length < 4) {
                    sender.sendMessage("\u00a7c\u7528\u6cd5: /se announce addline <ID> <\u5185\u5bb9>");
                    return true;
                }
                try {
                    int id = Integer.parseInt(args[2]);
                    String content = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
                    if (content.isEmpty()) {
                        sender.sendMessage("\u00a7c\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                        return true;
                    }
                    List<String> anns = this.plugin.getAnnouncements();
                    if (id <= 0 || id > anns.size()) {
                        sender.sendMessage("\u00a7c\u516c\u544aID\u4e0d\u5b58\u5728: " + id);
                        return true;
                    }
                    this.plugin.editAnnouncement(id, anns.get(id - 1) + "\n" + content);
                    sender.sendMessage("\u00a7a\u516c\u544a #" + id + " \u5df2\u8ffd\u52a0\u4e00\u884c\u3002");
                } catch (NumberFormatException e) {
                    sender.sendMessage("\u00a7c\u516c\u544aID\u5fc5\u987b\u662f\u6570\u5b57\u3002");
                }
                return true;
            }
            case "delline":
            case "popline": {
                if (args.length < 3) {
                    sender.sendMessage("\u00a7c\u7528\u6cd5: /se announce delline <ID>");
                    return true;
                }
                try {
                    int id = Integer.parseInt(args[2]);
                    List<String> anns = this.plugin.getAnnouncements();
                    if (id <= 0 || id > anns.size()) {
                        sender.sendMessage("\u00a7c\u516c\u544aID\u4e0d\u5b58\u5728: " + id);
                        return true;
                    }
                    String[] lines = anns.get(id - 1).split("\\n");
                    if (lines.length <= 1) {
                        sender.sendMessage("\u00a7c\u8be5\u516c\u544a\u53ea\u6709\u4e00\u884c\uff0c\u65e0\u6cd5\u518d\u5220\u9664\u6700\u4e0b\u65b9\u4e00\u884c\u3002");
                        return true;
                    }
                    StringBuilder builder = new StringBuilder();
                    for (int i = 0; i < lines.length - 1; ++i) {
                        if (i > 0) {
                            builder.append("\n");
                        }
                        builder.append(lines[i]);
                    }
                    this.plugin.editAnnouncement(id, builder.toString());
                    sender.sendMessage("\u00a7a\u516c\u544a #" + id + " \u5df2\u5220\u9664\u6700\u4e0b\u65b9\u4e00\u884c\u3002");
                } catch (NumberFormatException e) {
                    sender.sendMessage("\u00a7c\u516c\u544aID\u5fc5\u987b\u662f\u6570\u5b57\u3002");
                }
                return true;
            }
            case "edit": {
                if (args.length < 4) {
                    sender.sendMessage("\u00a7c\u7528\u6cd5: /se announce edit <ID> <\u5185\u5bb9>");
                    return true;
                }
                try {
                    int id = Integer.parseInt(args[2]);
                    String content = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
                    if (content.isEmpty()) {
                        sender.sendMessage("\u00a7c\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                        return true;
                    }
                    sender.sendMessage(this.plugin.editAnnouncement(id, content) ? "\u00a7a\u516c\u544a #" + id + " \u5df2\u66f4\u65b0\u3002" : "\u00a7c\u516c\u544aID\u4e0d\u5b58\u5728: " + id);
                } catch (NumberFormatException e) {
                    sender.sendMessage("\u00a7c\u516c\u544aID\u5fc5\u987b\u662f\u6570\u5b57\u3002");
                }
                return true;
            }
            case "del":
            case "delete":
            case "remove": {
                try {
                    int id = Integer.parseInt(args[2]);
                    sender.sendMessage(this.plugin.deleteAnnouncement(id) ? "\u00a7a\u516c\u544a #" + id + " \u5df2\u5220\u9664\u3002" : "\u00a7c\u516c\u544aID\u4e0d\u5b58\u5728: " + id);
                } catch (NumberFormatException e) {
                    sender.sendMessage("\u00a7c\u516c\u544aID\u5fc5\u987b\u662f\u6570\u5b57\u3002");
                }
                return true;
            }
            default:
                sender.sendMessage("\u00a7c\u7528\u6cd5: /se announce <add|addline|edit|del|delline> ...");
                return true;
        }
    }

    private boolean handleAnnouncements(CommandSender sender) {
        List<String> announcements = this.plugin.getAnnouncements();
        if (announcements.isEmpty()) {
            sender.sendMessage("\u00a77\u5f53\u524d\u6ca1\u6709\u516c\u544a\u3002");
            return true;
        }
        sender.sendMessage("\u00a76===== \u516c\u544a\u5217\u8868 =====");
        for (int i = 0; i < announcements.size(); ++i) {
            sender.sendMessage("\u00a7e#" + (i + 1) + " \u00a7f" + announcements.get(i));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return SUB_COMMANDS.stream().filter(cmd -> cmd.startsWith(partial)).collect(Collectors.toList());
        }
        if (args.length == 2 && "tax".equalsIgnoreCase(args[0]) && sender.hasPermission("exchange.admin")) {
            String partial = args[1];
            return Arrays.asList("0", "5", "10", "20").stream()
                .filter(value -> value.startsWith(partial))
                .collect(Collectors.toList());
        }
        return new ArrayList<String>();
    }
}
