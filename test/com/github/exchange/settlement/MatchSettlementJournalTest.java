package com.github.exchange.settlement;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

public final class MatchSettlementJournalTest {
    public static void main(String[] args) throws Exception {
        testPreparedAndInDoubtSurviveRestart();
        testResolvedDecisionsCanBeClearedAfterRestart();
        testSaveFailuresKeepOrdersLocked();
        testCorruptJournalFailsClosed();
        System.out.println("MatchSettlementJournalTest PASSED");
    }

    private static void testPreparedAndInDoubtSurviveRestart() throws Exception {
        Path directory = Files.createTempDirectory("match-journal-prepared-");
        Path file = directory.resolve("journal.properties");
        try {
            MatchSettlementJournal first = new MatchSettlementJournal(file);
            first.load();
            MatchSettlementJournal.PrepareResult prepared =
                first.prepare(101, 202);
            assert prepared.prepared();
            assert first.hasPending();
            assert first.isOrderLocked(101);
            assert first.isOrderLocked(202);
            assert !first.isOrderLocked(303);
            assert first.prepare(303, 404).status()
                == MatchSettlementJournal.PrepareStatus.BLOCKED;

            MatchSettlementJournal restarted =
                new MatchSettlementJournal(file);
            restarted.load();
            assert restarted.current().decision()
                == MatchSettlementJournal.Decision.PREPARED;
            assert restarted.isOrderLocked(101);
            assert restarted.isOrderLocked(202);
            assert restarted.retryClearResolvedDecision();
            assert restarted.hasPending()
                : "PREPARED must never auto-clear on restart";

            assert restarted.persistDecision(
                restarted.current().id(),
                MatchSettlementJournal.Decision.IN_DOUBT,
                "injected rollback failure"
            );
            MatchSettlementJournal restartedAgain =
                new MatchSettlementJournal(file);
            restartedAgain.load();
            assert restartedAgain.current().decision()
                == MatchSettlementJournal.Decision.IN_DOUBT;
            assert restartedAgain.retryClearResolvedDecision();
            assert restartedAgain.hasPending()
                : "IN_DOUBT must never auto-clear on restart";
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void testResolvedDecisionsCanBeClearedAfterRestart()
        throws Exception {
        for (MatchSettlementJournal.Decision decision :
            new MatchSettlementJournal.Decision[]{
                MatchSettlementJournal.Decision.COMMIT,
                MatchSettlementJournal.Decision.ROLLBACK
            }) {
            Path directory = Files.createTempDirectory(
                "match-journal-" + decision.name().toLowerCase() + "-"
            );
            Path file = directory.resolve("journal.properties");
            try {
                MatchSettlementJournal first =
                    new MatchSettlementJournal(file);
                first.load();
                MatchSettlementJournal.Entry entry =
                    first.prepare(11, 22).entry();
                assert first.persistDecision(entry.id(), decision, "resolved");

                MatchSettlementJournal restarted =
                    new MatchSettlementJournal(file);
                restarted.load();
                assert restarted.current().decision() == decision;
                assert restarted.retryClearResolvedDecision();
                assert !restarted.hasPending();

                MatchSettlementJournal cleared =
                    new MatchSettlementJournal(file);
                cleared.load();
                assert !cleared.hasPending();
            } finally {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            }
        }
    }

    private static void testSaveFailuresKeepOrdersLocked() throws Exception {
        AtomicLong clock = new AtomicLong(1000L);
        FaultPersistence storage = new FaultPersistence();
        MatchSettlementJournal journal = new MatchSettlementJournal(
            Path.of("ignored.properties"),
            storage,
            clock::getAndIncrement
        );
        journal.load();

        storage.failNextSave = true;
        MatchSettlementJournal.PrepareResult failedPrepare =
            journal.prepare(31, 32);
        assert failedPrepare.status()
            == MatchSettlementJournal.PrepareStatus.SAVE_FAILED;
        assert journal.hasPending();
        assert journal.isOrderLocked(31);
        assert journal.isOrderLocked(32);

        storage = new FaultPersistence();
        journal = new MatchSettlementJournal(
            Path.of("ignored.properties"),
            storage,
            clock::getAndIncrement
        );
        journal.load();
        MatchSettlementJournal.Entry entry = journal.prepare(41, 42).entry();
        storage.failNextSave = true;
        assert !journal.persistDecision(
            entry.id(),
            MatchSettlementJournal.Decision.COMMIT,
            ""
        );
        assert journal.current().decision()
            == MatchSettlementJournal.Decision.PREPARED;
        assert journal.isOrderLocked(41);
        assert journal.isOrderLocked(42);

        assert journal.persistDecision(
            entry.id(),
            MatchSettlementJournal.Decision.COMMIT,
            ""
        );
        storage.failNextSave = true;
        assert !journal.clear(entry.id());
        assert journal.current().decision()
            == MatchSettlementJournal.Decision.COMMIT;
        assert journal.isOrderLocked(41);
        assert journal.isOrderLocked(42);

        MatchSettlementJournal restarted = new MatchSettlementJournal(
            Path.of("ignored.properties"),
            storage,
            clock::getAndIncrement
        );
        restarted.load();
        assert restarted.current().decision()
            == MatchSettlementJournal.Decision.COMMIT;
        assert restarted.retryClearResolvedDecision();
        assert !restarted.hasPending();
    }

    private static void testCorruptJournalFailsClosed() throws Exception {
        Path directory = Files.createTempDirectory("match-journal-corrupt-");
        Path file = directory.resolve("journal.properties");
        Properties corrupt = new Properties();
        corrupt.setProperty("version", "1");
        corrupt.setProperty("active", "true");
        corrupt.setProperty("transaction-id", "not-a-uuid");
        corrupt.setProperty("buy-order-id", "1");
        corrupt.setProperty("sell-order-id", "2");
        corrupt.setProperty("decision", "PREPARED");
        corrupt.setProperty("reason", "");
        corrupt.setProperty("created-at", "1");
        corrupt.setProperty("updated-at", "1");
        try {
            try (OutputStream output = Files.newOutputStream(file)) {
                corrupt.store(output, "corrupt");
            }
            MatchSettlementJournal journal =
                new MatchSettlementJournal(file);
            boolean failedClosed = false;
            try {
                journal.load();
            } catch (IOException expected) {
                failedClosed = true;
            }
            assert failedClosed : "corrupt active journal must reject startup";
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static final class FaultPersistence
        implements MatchSettlementJournal.Persistence {
        private Properties stored;
        private boolean failNextSave;

        @Override
        public Properties load(Path file) {
            return copy(this.stored);
        }

        @Override
        public void save(Path file, Properties properties) throws IOException {
            if (this.failNextSave) {
                this.failNextSave = false;
                throw new IOException("injected persistence failure");
            }
            this.stored = copy(properties);
        }

        private static Properties copy(Properties source) {
            if (source == null) {
                return null;
            }
            Properties copy = new Properties();
            copy.putAll(source);
            return copy;
        }
    }
}
