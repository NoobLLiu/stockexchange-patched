/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 */
package com.github.exchange.adapter;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public interface VersionAdapter {
    public String itemToBase64(ItemStack var1);

    public ItemStack itemFromBase64(String var1);

    public void setInventoryTitle(Player var1, Inventory var2, String var3);

    public String getItemName(ItemStack var1);
}

