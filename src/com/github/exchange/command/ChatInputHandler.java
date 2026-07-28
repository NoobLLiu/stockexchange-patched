/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.player.AsyncPlayerChatEvent
 */
package com.github.exchange.command;

import com.github.exchange.StockExchangePlugin;
import com.github.exchange.gui.ExchangeGUI;
import com.github.exchange.manager.ItemManager;
import com.github.exchange.model.ExchangeItem;
import com.github.exchange.util.ItemDatabase;
import com.github.exchange.util.ItemSerializer;
import java.math.BigDecimal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;

public class ChatInputHandler
implements Listener {
    private final StockExchangePlugin plugin;
    private final AnvilInputGUI anvilInputGUI;
    private static final long TIMEOUT_MS = 300000L;

    public ChatInputHandler(StockExchangePlugin plugin) {
        this.plugin = plugin;
        this.anvilInputGUI = new AnvilInputGUI(plugin);
    }

    public void registerEvents() {
        Bukkit.getPluginManager().registerEvents(anvilInputGUI, plugin);
    }

    public void startInput(Player player, ExchangeItem exchangeItem, String action) {
        if (this.plugin.isBedrockPlayer(player)) {
            this.openBedrockPriceQuantityForm(player, exchangeItem, action);
            return;
        }
        player.closeInventory();
        anvilInputGUI.openInput(player, "\u00a7a\u8f93\u5165\u4ef7\u683c", String.valueOf(this.plugin.getPriceTick()), priceText -> {
            if (priceText == null || priceText.equalsIgnoreCase("cancel")) {
                player.sendMessage("\u00a7c\u64cd\u4f5c\u5df2\u53d6\u6d88\u3002");
                return;
            }
            BigDecimal price = this.parsePrice(player, priceText);
            if (price == null) return;
            anvilInputGUI.openInput(player, "\u00a7a\u8f93\u5165\u6570\u91cf", String.valueOf(this.plugin.getMaxOrderQuantity()), quantityText -> {
                if (quantityText == null || quantityText.equalsIgnoreCase("cancel")) {
                    player.sendMessage("\u00a7c\u64cd\u4f5c\u5df2\u53d6\u6d88\u3002");
                    return;
                }
                Integer quantity = this.parseQuantity(player, quantityText);
                if (quantity == null) return;
                this.executeInput(player, exchangeItem, action, price, quantity);
            });
        });
    }

    public void startSellInput(Player player, ExchangeItem exchangeItem) {
        this.startInput(player, exchangeItem, "sell");
    }

    public void startBuyInput(Player player, ExchangeItem exchangeItem) {
        this.startInput(player, exchangeItem, "buy");
    }

    public void startMarketBuyInput(Player player, ExchangeItem exchangeItem) {
        if (this.plugin.isBedrockPlayer(player)) {
            this.openBedrockQuantityForm(player, exchangeItem, "market_buy", null);
            return;
        }
        player.closeInventory();
        anvilInputGUI.openInput(player, "\u00a7a\u8f93\u5165\u6570\u91cf", String.valueOf(this.plugin.getMaxOrderQuantity()), quantityText -> {
            if (quantityText == null || quantityText.equalsIgnoreCase("cancel")) {
                player.sendMessage("\u00a7c\u64cd\u4f5c\u5df2\u53d6\u6d88\u3002");
                return;
            }
            Integer quantity = this.parseQuantity(player, quantityText);
            if (quantity == null) return;
            this.executeInput(player, exchangeItem, "market_buy", null, quantity);
        });
    }

    public void startMarketSearchInput(Player player) {
        if (this.plugin.isBedrockPlayer(player)) {
            this.openBedrockMarketSearchForm(player);
            return;
        }
        player.closeInventory();
        String placeholder = "\u8f93\u5165\u5173\u952e\u8bcd";
        anvilInputGUI.openInput(player, "\u00a7b\u641c\u7d22\u5546\u54c1", placeholder, query -> {
            if (query == null) {
                ExchangeGUI.openItemList(this.plugin, player);
                return;
            }
            if (query.isBlank() || query.equals(placeholder)) {
                player.sendMessage("\u00a7e\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                ExchangeGUI.openItemList(this.plugin, player);
                return;
            }
            ExchangeGUI.openCatalogSearchResults(this.plugin, player, query, 1);
        });
    }

    private void openBedrockPriceQuantityForm(Player player, ExchangeItem exchangeItem, String action) {
        player.closeInventory();
        CustomForm.Builder builder = CustomForm.builder()
            .title(action.equals("sell") ? "上架商品" : "提交买单")
            .input("价格（最小单位: " + this.plugin.getPriceTick() + "）", "请输入数字")
            .input("数量（最大: " + this.plugin.getMaxOrderQuantity() + "）", "请输入整数")
            .validResultHandler(response -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                BigDecimal price = this.parsePrice(player, response.asInput(0));
                if (price == null) {
                    this.openBedrockPriceQuantityForm(player, exchangeItem, action);
                    return;
                }
                Integer quantity = this.parseQuantity(player, response.asInput(1));
                if (quantity == null) {
                    this.openBedrockPriceQuantityForm(player, exchangeItem, action);
                    return;
                }
                this.executeInput(player, exchangeItem, action, price, quantity);
            }));

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    private void openBedrockQuantityForm(Player player, ExchangeItem exchangeItem, String action, BigDecimal price) {
        player.closeInventory();
        CustomForm.Builder builder = CustomForm.builder()
            .title("买入商品")
            .input("买入数量（最大: " + this.plugin.getMaxOrderQuantity() + "）", "请输入整数")
            .validResultHandler(response -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                Integer quantity = this.parseQuantity(player, response.asInput(0));
                if (quantity == null) {
                    this.openBedrockQuantityForm(player, exchangeItem, action, price);
                    return;
                }
                this.executeInput(player, exchangeItem, action, price, quantity);
            }));

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    private void openBedrockMarketSearchForm(Player player) {
        player.closeInventory();
        CustomForm.Builder builder = CustomForm.builder()
            .title("\u641c\u7d22\u5546\u54c1")
            .input("\u7269\u54c1\u540d\u79f0\u6216 ID \u5173\u952e\u8bcd", "\u4f8b\u5982\uff1a\u94bb\u77f3\u3001diamond sword\u300111")
            .validResultHandler(response -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                String query = response.asInput(0);
                if (query == null || query.isBlank()) {
                    player.sendMessage("\u00a7e\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                    ExchangeGUI.openItemList(this.plugin, player);
                    return;
                }
                ExchangeGUI.openCatalogSearchResults(this.plugin, player, query, 1);
            }))
            .closedResultHandler(() -> Bukkit.getScheduler().runTask(
                this.plugin,
                () -> ExchangeGUI.openItemList(this.plugin, player)
            ));

        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }

    private BigDecimal parsePrice(Player player, String rawPrice) {
        try {
            if (rawPrice == null || rawPrice.isBlank()) {
                player.sendMessage("\u00a7c\u4ef7\u683c\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                return null;
            }
            BigDecimal price = new BigDecimal(rawPrice.trim());
            if (price.compareTo(BigDecimal.valueOf(this.plugin.getMinPrice())) < 0 || price.compareTo(BigDecimal.valueOf(this.plugin.getMaxPrice())) > 0) {
                player.sendMessage("\u00a7c\u4ef7\u683c\u5fc5\u987b\u5728 " + this.plugin.getMinPrice() + " \u5230 " + this.plugin.getMaxPrice() + " \u4e4b\u95f4\u3002");
                return null;
            }
            BigDecimal remainder = price.remainder(BigDecimal.valueOf(this.plugin.getPriceTick()));
            if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                player.sendMessage("\u00a7c\u4ef7\u683c\u5fc5\u987b\u662f " + this.plugin.getPriceTick() + " \u7684\u6574\u6570\u500d\u3002");
                return null;
            }
            return price;
        }
        catch (NumberFormatException e) {
            player.sendMessage("\u00a7c\u65e0\u6548\u7684\u4ef7\u683c\u683c\u5f0f\u3002\u8bf7\u8f93\u5165\u6570\u5b57\u3002");
            return null;
        }
    }

    private Integer parseQuantity(Player player, String rawQuantity) {
        try {
            if (rawQuantity == null || rawQuantity.isBlank()) {
                player.sendMessage("\u00a7c\u6570\u91cf\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                return null;
            }
            int quantity = Integer.parseInt(rawQuantity.trim());
            if (quantity <= 0 || quantity > this.plugin.getMaxOrderQuantity()) {
                player.sendMessage("\u00a7c\u6570\u91cf\u5fc5\u987b\u5728 1 \u5230 " + this.plugin.getMaxOrderQuantity() + " \u4e4b\u95f4\u3002");
                return null;
            }
            return quantity;
        }
        catch (NumberFormatException e) {
            player.sendMessage("\u00a7c\u65e0\u6548\u7684\u6570\u91cf\u683c\u5f0f\u3002\u8bf7\u8f93\u5165\u6574\u6570\u3002");
            return null;
        }
    }

    private void executeInput(Player player, ExchangeItem exchangeItem, String action, BigDecimal price, int quantity) {
        String result;
        if (action.equals("market_buy")) {
            result = this.plugin.getOrderManager().marketBuy(player, exchangeItem, quantity);
        } else if (action.equals("buy")) {
            result = this.plugin.getOrderManager().placeBuyOrder(player, exchangeItem, price, quantity);
        } else {
            result = this.plugin.getOrderManager().placeSellOrder(player, exchangeItem, price, quantity);
        }
        player.sendMessage(result);
    }

    public void startAddItemSearchInput(Player player) {
        if (this.plugin.isBedrockPlayer(player)) {
            this.openBedrockAddItemSearchForm(player);
            return;
        }
        player.closeInventory();
        anvilInputGUI.openInput(player, "\u00a7e\u641c\u7d22\u6dfb\u52a0", "\u8f93\u5165\u7269\u54c1\u540d\u79f0\u6216 ID", query -> {
            if (query == null) {
                ExchangeGUI.openAddItemMenu(this.plugin, player);
                return;
            }
            if (query.isBlank()) {
                player.sendMessage("\u00a7e\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                ExchangeGUI.openAddItemMenu(this.plugin, player);
                return;
            }
            ItemDatabase.ItemEntry entry = this.plugin.getItemDatabase().search(query.trim());
            if (entry == null) {
                player.sendMessage("\u00a7c\u672a\u627e\u5230\u5339\u914d\u7684\u7269\u54c1\uff1a" + query.trim());
                ExchangeGUI.openAddItemMenu(this.plugin, player);
                return;
            }
            org.bukkit.inventory.ItemStack baseItem = this.plugin.getItemDatabase().createItemStack(entry);
            if (baseItem == null) {
                player.sendMessage("\u00a7c\u65e0\u6cd5\u521b\u5efa\u7269\u54c1\uff1a" + entry.getName() + " (" + entry.getId() + ")");
                ExchangeGUI.openAddItemMenu(this.plugin, player);
                return;
            }
            ItemManager.RegisterResult result = this.plugin.getItemManager().registerCatalogItem(player, baseItem);
            player.sendMessage(result.getMessage());
            ExchangeGUI.openAddItemMenu(this.plugin, player);
        });
    }

    private void openBedrockAddItemSearchForm(Player player) {
        player.closeInventory();
        CustomForm.Builder builder = CustomForm.builder()
            .title("\u641c\u7d22\u6dfb\u52a0")
            .input("\u8f93\u5165\u7269\u54c1\u540d\u79f0\u6216 ID", "\u4f8b\u5982\uff1a\u94bb\u77f3\u3001diamond sword\u3001white_cushion")
            .validResultHandler(response -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                String query = response.asInput(0);
                if (query == null || query.isBlank()) {
                    player.sendMessage("\u00a7e\u641c\u7d22\u5173\u952e\u8bcd\u4e0d\u80fd\u4e3a\u7a7a\u3002");
                    ExchangeGUI.openAddItemMenu(this.plugin, player);
                    return;
                }
                ItemDatabase.ItemEntry entry = this.plugin.getItemDatabase().search(query.trim());
                if (entry == null) {
                    player.sendMessage("\u00a7c\u672a\u627e\u5230\u5339\u914d\u7684\u7269\u54c1\uff1a" + query.trim());
                    ExchangeGUI.openAddItemMenu(this.plugin, player);
                    return;
                }
                ItemStack baseItem = this.plugin.getItemDatabase().createItemStack(entry);
                if (baseItem == null) {
                    player.sendMessage("\u00a7c\u65e0\u6cd5\u521b\u5efa\u7269\u54c1\uff1a" + entry.getName() + " (" + entry.getId() + ")");
                    ExchangeGUI.openAddItemMenu(this.plugin, player);
                    return;
                }
                ItemManager.RegisterResult result = this.plugin.getItemManager().registerCatalogItem(player, baseItem);
                player.sendMessage(result.getMessage());
                ExchangeGUI.openAddItemMenu(this.plugin, player);
            }))
            .closedResultHandler(() -> Bukkit.getScheduler().runTask(
                this.plugin,
                () -> ExchangeGUI.openAddItemMenu(this.plugin, player)
            ));
        FloodgateApi.getInstance().sendForm(player.getUniqueId(), builder);
    }
}
