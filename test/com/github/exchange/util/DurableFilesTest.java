package com.github.exchange.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DurableFilesTest {
    public static void main(String[] args) throws Exception {
        testSuccessfulSequence();
        testTemporaryForceFailureStopsBeforeMove();
        testMoveFailureMarksTargetUncertain();
        testTargetForceFailureMarksTargetUncertain();
        testParentForceFailureMarksTargetUncertain();
        System.out.println("DurableFilesTest PASSED");
    }

    private static void testSuccessfulSequence() throws Exception {
        RecordingOperations operations = new RecordingOperations();
        DurableFiles.replace(
            Path.of("temporary"),
            Path.of("target"),
            operations
        );
        assert operations.calls.equals(List.of(
            "force:temporary",
            "move:temporary->target",
            "force:target",
            "force-parent:target"
        ));
    }

    private static void testTemporaryForceFailureStopsBeforeMove() {
        RecordingOperations operations = new RecordingOperations();
        operations.failAtCall = 1;
        DurableFiles.ReplaceException exception = assertThrows(() ->
            DurableFiles.replace(
            Path.of("temporary"),
            Path.of("target"),
            operations
        ));
        assert !exception.isTargetStateUncertain();
        assert operations.calls.equals(List.of("force:temporary"));
    }

    private static void testMoveFailureMarksTargetUncertain() {
        RecordingOperations operations = new RecordingOperations();
        operations.failAtCall = 2;
        DurableFiles.ReplaceException exception = assertThrows(() ->
            DurableFiles.replace(
            Path.of("temporary"),
            Path.of("target"),
            operations
        ));
        assert exception.isTargetStateUncertain();
        assert operations.calls.equals(List.of(
            "force:temporary",
            "move:temporary->target"
        ));
    }

    private static void testTargetForceFailureMarksTargetUncertain() {
        RecordingOperations operations = new RecordingOperations();
        operations.failAtCall = 3;
        DurableFiles.ReplaceException exception = assertThrows(() ->
            DurableFiles.replace(
            Path.of("temporary"),
            Path.of("target"),
            operations
        ));
        assert exception.isTargetStateUncertain();
        assert operations.calls.equals(List.of(
            "force:temporary",
            "move:temporary->target",
            "force:target"
        ));
    }

    private static void testParentForceFailureMarksTargetUncertain() {
        RecordingOperations operations = new RecordingOperations();
        operations.failAtCall = 4;
        DurableFiles.ReplaceException exception = assertThrows(() ->
            DurableFiles.replace(
                Path.of("temporary"),
                Path.of("target"),
                operations
            )
        );
        assert exception.isTargetStateUncertain();
        assert operations.calls.equals(List.of(
            "force:temporary",
            "move:temporary->target",
            "force:target",
            "force-parent:target"
        ));
    }

    private static DurableFiles.ReplaceException assertThrows(
        ThrowingRunnable action
    ) {
        try {
            action.run();
        } catch (DurableFiles.ReplaceException expected) {
            return expected;
        } catch (IOException unexpected) {
            throw new AssertionError(
                "Expected ReplaceException but got "
                    + unexpected.getClass().getName(),
                unexpected
            );
        }
        throw new AssertionError("Expected ReplaceException");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static final class RecordingOperations
        implements DurableFiles.Operations {
        private final List<String> calls = new ArrayList<String>();
        private int failAtCall;

        @Override
        public void forceFile(Path file) throws IOException {
            this.record("force:" + file);
        }

        @Override
        public void moveReplacing(Path source, Path target)
            throws IOException {
            this.record("move:" + source + "->" + target);
        }

        @Override
        public void forceParentDirectory(Path target) throws IOException {
            this.record("force-parent:" + target);
        }

        private void record(String call) throws IOException {
            this.calls.add(call);
            if (this.failAtCall > 0
                && this.calls.size() == this.failAtCall) {
                throw new IOException("injected failure at " + call);
            }
        }
    }
}
