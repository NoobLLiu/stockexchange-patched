package com.github.exchange.manager;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

public final class SellBuyerTrackerTest {
    public static void main(String[] args) throws Exception {
        File tempDir = Files.createTempDirectory("sell-buyer-tracker-test").toFile();
        File dataFile = new File(tempDir, "sell-buyers.yml");
        Logger logger = Logger.getLogger(SellBuyerTrackerTest.class.getName());
        UUID seller = UUID.randomUUID();
        UUID buyerA = UUID.randomUUID();
        UUID buyerB = UUID.randomUUID();

        SellBuyerTracker tracker = new SellBuyerTracker(dataFile, logger);
        tracker.load();

        // Self-purchase is never a new buyer and grants nothing.
        assert !tracker.recordNewBuyer(seller, "Seller", seller);
        assert !tracker.hasBuyerToday(seller, seller);

        // First sale to buyer A records the buyer; growth is not granted here
        // because MGActivitys is absent in the test JVM.
        assert !tracker.recordNewBuyer(seller, "Seller", buyerA);
        assert tracker.hasBuyerToday(seller, buyerA);

        // Repeating buyer A does not create another entry.
        assert !tracker.recordNewBuyer(seller, "Seller", buyerA);

        // A second distinct buyer is recorded independently.
        assert !tracker.recordNewBuyer(seller, "Seller", buyerB);
        assert tracker.hasBuyerToday(seller, buyerA);
        assert tracker.hasBuyerToday(seller, buyerB);

        // Persisted lists survive a reload on the same day.
        SellBuyerTracker reloaded = new SellBuyerTracker(dataFile, logger);
        reloaded.load();
        assert reloaded.hasBuyerToday(seller, buyerA);
        assert reloaded.hasBuyerToday(seller, buyerB);

        // A stale date triggers the daily refresh: the whole list is cleared.
        YamlConfiguration stale = new YamlConfiguration();
        stale.set("date", "2000-01-01");
        stale.set("sellers." + seller, java.util.List.of(buyerA.toString()));
        stale.save(dataFile);
        SellBuyerTracker nextDay = new SellBuyerTracker(dataFile, logger);
        nextDay.load();
        assert !nextDay.hasBuyerToday(seller, buyerA);
        assert !nextDay.hasBuyerToday(seller, buyerB);
        assert !nextDay.dateKey().equals("2000-01-01");
    }
}
