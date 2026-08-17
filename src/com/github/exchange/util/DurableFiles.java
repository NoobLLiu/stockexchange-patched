package com.github.exchange.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Shared crash-durable file replacement helpers for asset-bearing state.
 */
public final class DurableFiles {
    private static final Operations SYSTEM_OPERATIONS = new SystemOperations();

    private DurableFiles() {
    }

    public static void replace(Path temporary, Path target) throws IOException {
        replace(temporary, target, SYSTEM_OPERATIONS);
    }

    static void replace(
        Path temporary,
        Path target,
        Operations operations
    ) throws IOException {
        Objects.requireNonNull(temporary, "temporary");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operations, "operations");
        try {
            operations.forceFile(temporary);
        } catch (IOException exception) {
            throw new ReplaceException(
                "Failed to force temporary file before replacement.",
                false,
                exception
            );
        }
        try {
            operations.moveReplacing(temporary, target);
        } catch (IOException exception) {
            throw new ReplaceException(
                "Atomic replacement failed; target state is uncertain.",
                true,
                exception
            );
        }
        try {
            operations.forceFile(target);
        } catch (IOException exception) {
            throw new ReplaceException(
                "Replacement completed but target force failed.",
                true,
                exception
            );
        }
        try {
            operations.forceParentDirectory(target);
        } catch (IOException exception) {
            throw new ReplaceException(
                "Replacement completed but parent directory force failed.",
                true,
                exception
            );
        }
    }

    public static void forceFile(Path file) throws IOException {
        SYSTEM_OPERATIONS.forceFile(file);
    }

    public static void moveReplacing(Path source, Path target)
        throws IOException {
        SYSTEM_OPERATIONS.moveReplacing(source, target);
    }

    public static void forceDirectoryIfSupported(Path directory)
        throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(
                directory,
                StandardOpenOption.READ
            )) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException ignored) {
            // Windows commonly disallows opening a directory as a FileChannel.
        }
    }

    interface Operations {
        void forceFile(Path file) throws IOException;

        void moveReplacing(Path source, Path target) throws IOException;

        void forceParentDirectory(Path target) throws IOException;
    }

    public static final class ReplaceException extends IOException {
        private final boolean targetStateUncertain;

        private ReplaceException(
            String message,
            boolean targetStateUncertain,
            IOException cause
        ) {
            super(message, cause);
            this.targetStateUncertain = targetStateUncertain;
        }

        public boolean isTargetStateUncertain() {
            return this.targetStateUncertain;
        }
    }

    private static final class SystemOperations implements Operations {
        @Override
        public void forceFile(Path file) throws IOException {
            try (FileChannel channel = FileChannel.open(
                    file,
                    StandardOpenOption.WRITE
                )) {
                channel.force(true);
            }
        }

        @Override
        public void moveReplacing(Path source, Path target)
            throws IOException {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        }

        @Override
        public void forceParentDirectory(Path target) throws IOException {
            DurableFiles.forceDirectoryIfSupported(
                target.toAbsolutePath().getParent()
            );
        }
    }
}
