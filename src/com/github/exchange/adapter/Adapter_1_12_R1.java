package com.github.exchange.adapter;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class Adapter_1_12_R1
implements VersionAdapter {
    @Override
    public String itemToBase64(ItemStack item) {
        return VersionlessAdapterSupport.itemToBase64(item);
    }

    @Override
    public ItemStack itemFromBase64(String base64) {
        return VersionlessAdapterSupport.itemFromBase64(base64);
    }

    @Override
    public void setInventoryTitle(Player player, Inventory inv, String title) {
        VersionlessAdapterSupport.setInventoryTitle(player, inv, title);
    }

    @Override
    public String getItemName(ItemStack item) {
        return VersionlessAdapterSupport.getItemName(item);
    }
}
