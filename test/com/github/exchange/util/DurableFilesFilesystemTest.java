package com.github.exchange.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DurableFilesFilesystemTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("durable-files-");
        Path target = directory.resolve("state.yml");
        Path temporary = directory.resolve("state.yml.tmp");
        try {
            Files.writeString(
                target,
                "old",
                StandardCharsets.UTF_8
            );
            Files.writeString(
                temporary,
                "new",
                StandardCharsets.UTF_8
            );
            DurableFiles.replace(temporary, target);
            assert !Files.exists(temporary);
            assert "new".equals(
                Files.readString(target, StandardCharsets.UTF_8)
            );
            System.out.println("DurableFilesFilesystemTest PASSED");
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(target);
            Files.deleteIfExists(directory);
        }
    }
}
