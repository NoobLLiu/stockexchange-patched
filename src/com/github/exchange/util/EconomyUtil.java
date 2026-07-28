/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.milkbowl.vault.economy.Economy
 *  org.bukkit.Bukkit
 *  org.bukkit.OfflinePlayer
 */
package com.github.exchange.util;

import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class EconomyUtil {
    private static Economy economy;

    public static void init(Economy eco) {
        economy = eco;
    }

    public static boolean hasBalance(UUID uuid, BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)uuid);
        return economy.has(player, amount.doubleValue());
    }

    public static boolean withdraw(UUID uuid, BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)uuid);
        return economy.withdrawPlayer(player, amount.doubleValue()).transactionSuccess();
    }

    public static boolean deposit(UUID uuid, BigDecimal amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)uuid);
        return economy.depositPlayer(player, amount.doubleValue()).transactionSuccess();
    }

    public static BigDecimal getBalance(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer((UUID)uuid);
        return BigDecimal.valueOf(economy.getBalance(player));
    }
}

