package com.github.exchange.manager;

import cn.gmzc.mgactivitys.MGActivitysPlugin;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Tracks, per seller, which other players bought from them on the current day.
 *
 * <p>A successful sale grants the seller growth only when the buyer is new
 * for that day; the buyer list refreshes on the next LocalDate. The growth
 * amount (50) and the 300/day cap are enforced by the MGActivitys "sellItem"
 * listener configuration.
 */
public final class SellBuyerTracker {
    public static final String GROWTH_METHOD = "sellItem";

    private final File dataFile;
    private final Logger logger;
    private final Map<String, Set<String>> buyersBySeller = new HashMap<>();
    private String dateKey = LocalDate.now().toString();

    public SellBuyerTracker(File dataFile, Logger logger) {
        this.dataFile = dataFile;
        this.logger = logger;
    }

    public void load() {
        if (dataFile == null || !dataFile.isFile()) {
            rolloverIfNeeded();
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
            String storedDate = config.getString("date");
            if (storedDate != null && !storedDate.isBlank()) {
                this.dateKey = storedDate;
            }
            ConfigurationSection sellers = config.getConfigurationSection("sellers");
            if (sellers != null) {
                for (String seller : sellers.getKeys(false)) {
                    Set<String> buyers = new HashSet<>(sellers.getStringList(seller));
                    if (!buyers.isEmpty()) {
                        this.buyersBySeller.put(seller, buyers);
                    }
                }
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to load sell-buyers.yml: " + dataFile, ex);
        }
        rolloverIfNeeded();
    }

    public synchronized void save() {
        if (dataFile == null) {
            return;
        }
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.set("date", this.dateKey);
            ConfigurationSection sellers = config.createSection("sellers");
            for (Map.Entry<String, Set<String>> entry : this.buyersBySeller.entrySet()) {
                sellers.set(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            config.save(dataFile);
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to save sell-buyers.yml: " + dataFile, ex);
        }
    }

    /**
     * Records a successful sale and grants growth when the buyer is new for
     * the seller on the current day.
     *
     * @return true when growth was granted, false otherwise (duplicate buyer,
     *         self-purchase, disabled/capped growth method, or missing API)
     */
    public synchronized boolean recordNewBuyer(UUID sellerUuid, String sellerName, UUID buyerUuid) {
        rolloverIfNeeded();
        if (sellerUuid == null || buyerUuid == null || sellerUuid.equals(buyerUuid)) {
            return false;
        }
        String sellerKey = sellerUuid.toString();
        Set<String> buyers = this.buyersBySeller.computeIfAbsent(sellerKey, key -> new HashSet<>());
        if (!buyers.add(buyerUuid.toString())) {
            return false;
        }
        save();
        boolean granted = grant(sellerName);
        logger.info("[GrowthAudit] SELL_NEW_BUYER seller=" + sellerUuid
            + " buyer=" + buyerUuid + " granted=" + granted);
        return granted;
    }

    public synchronized boolean hasBuyerToday(UUID sellerUuid, UUID buyerUuid) {
        if (sellerUuid == null || buyerUuid == null) {
            return false;
        }
        rolloverIfNeeded();
        Set<String> buyers = this.buyersBySeller.get(sellerUuid.toString());
        return buyers != null && buyers.contains(buyerUuid.toString());
    }

    public synchronized String dateKey() {
        return this.dateKey;
    }

    private void rolloverIfNeeded() {
        String today = LocalDate.now().toString();
        if (!today.equals(this.dateKey)) {
            this.buyersBySeller.clear();
            this.dateKey = today;
            save();
        }
    }

    private boolean grant(String sellerName) {
        if (sellerName == null || sellerName.isBlank()) {
            return false;
        }
        try {
            MGActivitysPlugin activityPlugin = MGActivitysPlugin.getInstance();
            return activityPlugin != null
                && activityPlugin.addActivity(sellerName, GROWTH_METHOD);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Failed to grant sellItem growth for " + sellerName, ex);
            return false;
        }
    }
}
