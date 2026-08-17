package com.github.exchange.settlement;

import com.github.exchange.util.DurableFiles;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * A single-entry write-ahead journal for market settlement.
 *
 * <p>The journal is intentionally independent from the physical warehouse
 * journal. Every match writes PREPARED before either virtual escrow or a
 * physical chest can change. An unresolved entry therefore blocks new matches
 * and locks both referenced orders across restarts.</p>
 */
public final class MatchSettlementJournal {
    private static final String VERSION = "1";

    private final Path file;
    private final Persistence persistence;
    private final LongSupplier clock;
    private Entry pending;
    private String lastFailure = "";

    public MatchSettlementJournal(Path file) {
        this(file, new FilePersistence(), System::currentTimeMillis);
    }

    MatchSettlementJournal(
        Path file,
        Persistence persistence,
        LongSupplier clock
    ) {
        this.file = Objects.requireNonNull(file, "file");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void load() throws IOException {
        Properties properties = this.persistence.load(this.file);
        if (properties == null) {
            this.pending = null;
            this.lastFailure = "";
            return;
        }
        String version = require(properties, "version");
        if (!VERSION.equals(version)) {
            throw new IOException("Unsupported settlement journal version: " + version);
        }
        String active = require(properties, "active");
        if ("false".equals(active)) {
            this.pending = null;
            this.lastFailure = "";
            return;
        }
        if (!"true".equals(active)) {
            throw new IOException("Invalid settlement journal active flag");
        }
        try {
            String id = require(properties, "transaction-id");
            UUID.fromString(id);
            int buyOrderId = positiveInt(properties, "buy-order-id");
            int sellOrderId = positiveInt(properties, "sell-order-id");
            Decision decision = Decision.valueOf(require(properties, "decision"));
            String reason = requirePresent(properties, "reason");
            long createdAt = positiveLong(properties, "created-at");
            long updatedAt = positiveLong(properties, "updated-at");
            if (updatedAt < createdAt) {
                throw new IllegalArgumentException("updated-at precedes created-at");
            }
            this.pending = new Entry(
                id,
                buyOrderId,
                sellOrderId,
                decision,
                reason,
                createdAt,
                updatedAt
            );
            this.lastFailure = "";
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid active settlement journal", exception);
        }
    }

    public synchronized PrepareResult prepare(int buyOrderId, int sellOrderId) {
        if (buyOrderId <= 0 || sellOrderId <= 0) {
            return new PrepareResult(
                PrepareStatus.INVALID,
                this.pending,
                "order ids must be positive"
            );
        }
        if (this.pending != null) {
            return new PrepareResult(
                PrepareStatus.BLOCKED,
                this.pending,
                "another settlement is unresolved"
            );
        }
        long now = Math.max(1L, this.clock.getAsLong());
        Entry prepared = new Entry(
            UUID.randomUUID().toString(),
            buyOrderId,
            sellOrderId,
            Decision.PREPARED,
            "",
            now,
            now
        );
        try {
            this.persistence.save(this.file, serialize(prepared));
            this.pending = prepared;
            this.lastFailure = "";
            return new PrepareResult(PrepareStatus.PREPARED, prepared, "");
        } catch (IOException exception) {
            this.pending = prepared.withDecision(
                Decision.IN_DOUBT,
                "PREPARED persistence failed",
                now
            );
            this.lastFailure = failureMessage(exception);
            return new PrepareResult(
                PrepareStatus.SAVE_FAILED,
                this.pending,
                this.lastFailure
            );
        }
    }

    public synchronized boolean persistDecision(
        String transactionId,
        Decision decision,
        String reason
    ) {
        if (this.pending == null
            || transactionId == null
            || !this.pending.id().equals(transactionId)
            || decision == null
            || decision == Decision.PREPARED) {
            return false;
        }
        long updatedAt = Math.max(
            this.pending.createdAtMillis(),
            Math.max(1L, this.clock.getAsLong())
        );
        Entry decided = this.pending.withDecision(
            decision,
            reason == null ? "" : reason,
            updatedAt
        );
        try {
            this.persistence.save(this.file, serialize(decided));
            this.pending = decided;
            this.lastFailure = "";
            return true;
        } catch (IOException exception) {
            this.lastFailure = failureMessage(exception);
            return false;
        }
    }

    public synchronized boolean clear(String transactionId) {
        if (this.pending == null
            || transactionId == null
            || !this.pending.id().equals(transactionId)) {
            return false;
        }
        Entry previous = this.pending;
        try {
            this.persistence.save(this.file, cleared());
            this.pending = null;
            this.lastFailure = "";
            return true;
        } catch (IOException exception) {
            this.pending = previous;
            this.lastFailure = failureMessage(exception);
            return false;
        }
    }

    /**
     * Clears only a durable terminal decision. PREPARED and IN_DOUBT are
     * deliberately retained for manual recovery.
     */
    public synchronized boolean retryClearResolvedDecision() {
        if (this.pending == null
            || (this.pending.decision() != Decision.COMMIT
                && this.pending.decision() != Decision.ROLLBACK)) {
            return true;
        }
        return this.clear(this.pending.id());
    }

    public synchronized boolean hasPending() {
        return this.pending != null;
    }

    public synchronized boolean isOrderLocked(int orderId) {
        return orderId > 0
            && this.pending != null
            && (this.pending.buyOrderId() == orderId
                || this.pending.sellOrderId() == orderId);
    }

    public synchronized Entry current() {
        return this.pending;
    }

    public synchronized String lastFailure() {
        return this.lastFailure;
    }

    private static Properties serialize(Entry entry) {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("active", "true");
        properties.setProperty("transaction-id", entry.id());
        properties.setProperty(
            "buy-order-id",
            Integer.toString(entry.buyOrderId())
        );
        properties.setProperty(
            "sell-order-id",
            Integer.toString(entry.sellOrderId())
        );
        properties.setProperty("decision", entry.decision().name());
        properties.setProperty("reason", entry.reason());
        properties.setProperty(
            "created-at",
            Long.toString(entry.createdAtMillis())
        );
        properties.setProperty(
            "updated-at",
            Long.toString(entry.updatedAtMillis())
        );
        return properties;
    }

    private static Properties cleared() {
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("active", "false");
        return properties;
    }

    private static String require(Properties properties, String key) {
        String value = requirePresent(properties, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank settlement journal field: " + key);
        }
        return value;
    }

    private static String requirePresent(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing settlement journal field: " + key);
        }
        return value;
    }

    private static int positiveInt(Properties properties, String key) {
        int value = Integer.parseInt(require(properties, key));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static long positiveLong(Properties properties, String key) {
        long value = Long.parseLong(require(properties, key));
        if (value <= 0L) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static String failureMessage(IOException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName()
            : message;
    }

    public enum Decision {
        PREPARED,
        COMMIT,
        ROLLBACK,
        IN_DOUBT
    }

    public enum PrepareStatus {
        PREPARED,
        BLOCKED,
        INVALID,
        SAVE_FAILED
    }

    public record PrepareResult(
        PrepareStatus status,
        Entry entry,
        String reason
    ) {
        public boolean prepared() {
            return this.status == PrepareStatus.PREPARED;
        }
    }

    public record Entry(
        String id,
        int buyOrderId,
        int sellOrderId,
        Decision decision,
        String reason,
        long createdAtMillis,
        long updatedAtMillis
    ) {
        private Entry withDecision(
            Decision nextDecision,
            String nextReason,
            long nextUpdatedAt
        ) {
            return new Entry(
                this.id,
                this.buyOrderId,
                this.sellOrderId,
                nextDecision,
                nextReason,
                this.createdAtMillis,
                nextUpdatedAt
            );
        }
    }

    interface Persistence {
        Properties load(Path file) throws IOException;

        void save(Path file, Properties properties) throws IOException;
    }

    private static final class FilePersistence implements Persistence {
        @Override
        public Properties load(Path file) throws IOException {
            if (!Files.exists(file)) {
                return null;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
            return properties;
        }

        @Override
        public void save(Path file, Properties properties) throws IOException {
            Path parent = file.toAbsolutePath().getParent();
            if (parent == null) {
                throw new IOException("Settlement journal has no parent directory");
            }
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(
                parent,
                file.getFileName().toString() + ".tmp-",
                ".properties"
            );
            try {
                try (FileOutputStream output =
                    new FileOutputStream(temporary.toFile())) {
                    properties.store(output, "StockExchange settlement journal");
                    output.getChannel().force(true);
                }
                DurableFiles.replace(temporary, file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
