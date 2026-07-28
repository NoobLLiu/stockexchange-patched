package com.github.exchange.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class AnvilInputGUI implements Listener {
    private final Plugin plugin;
    private final NamespacedKey inputMarkerKey;
    private final Map<UUID, Consumer<String>> callbacks = new HashMap<>();

    public AnvilInputGUI(Plugin plugin) {
        this.plugin = plugin;
        this.inputMarkerKey = new NamespacedKey(plugin, "text-input");
    }

    public void openInput(Player player, String title, String placeholder, Consumer<String> callback) {
        player.closeInventory();
        callbacks.put(player.getUniqueId(), callback);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || callbacks.get(player.getUniqueId()) != callback) {
                return;
            }

            ItemStack input = createInputItem(placeholder);
            var view = MenuType.ANVIL.builder()
                .title(Component.text(title))
                .build(player);
            configure(view);
            view.getTopInventory().setItem(0, input);
            view.open();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepare(PrepareAnvilEvent event) {
        if (event.getView().getPlayer() instanceof Player player
            && callbacks.containsKey(player.getUniqueId())) {
            configure(event.getView());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
            || event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        Consumer<String> callback = callbacks.get(player.getUniqueId());
        if (callback == null) {
            return;
        }

        event.setCancelled(true);
        if (event.getRawSlot() != 2) {
            player.updateInventory();
            return;
        }

        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType().isAir()) {
            return;
        }

        String text = event.getView() instanceof AnvilView anvil ? anvil.getRenameText() : null;
        callbacks.remove(player.getUniqueId(), callback);
        discardOutput(event.getView());
        removeMarkedItems(player, event.getView());
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            if (player.isOnline()) {
                callback.accept(text);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
            && event.getView().getTopInventory().getType() == InventoryType.ANVIL
            && callbacks.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            player.updateInventory();
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
            || event.getView().getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        Consumer<String> callback = callbacks.remove(player.getUniqueId());
        if (callback == null) {
            return;
        }

        discardOutput(event.getView());
        removeMarkedItems(player, event.getView());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                callback.accept(null);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        callbacks.remove(event.getPlayer().getUniqueId());
    }

    private ItemStack createInputItem(String text) {
        ItemStack input = ItemStack.of(Material.PAPER);
        ItemMeta meta = input.getItemMeta();
        meta.displayName(Component.text(text == null ? "" : text, NamedTextColor.GRAY));
        meta.getPersistentDataContainer().set(inputMarkerKey, PersistentDataType.BYTE, (byte) 1);
        input.setItemMeta(meta);
        return input;
    }

    private void removeMarkedItems(Player player, InventoryView view) {
        for (int slot = 0; slot < view.getTopInventory().getSize(); slot++) {
            ItemStack item = view.getTopInventory().getItem(slot);
            if (isMarked(item)) {
                view.getTopInventory().setItem(slot, null);
            }
        }
        if (isMarked(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isMarked(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
        player.updateInventory();
    }

    private void discardOutput(InventoryView view) {
        view.getTopInventory().setItem(2, null);
    }

    private boolean isMarked(ItemStack item) {
        return item != null
            && !item.getType().isAir()
            && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(
                inputMarkerKey,
                PersistentDataType.BYTE
            );
    }

    private void configure(InventoryView view) {
        if (view instanceof AnvilView anvil) {
            anvil.setRepairCost(0);
            anvil.setRepairItemCountCost(0);
            anvil.setMaximumRepairCost(Integer.MAX_VALUE);
            anvil.bypassEnchantmentLevelRestriction(true);
        }
    }
}
