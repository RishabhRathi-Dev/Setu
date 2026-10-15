package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LegacyLogMigrationTests {
    @TempDir Path directory;

    @Test void importsOrderedSegmentsIntoFreshQueuesWithoutChangingTheSource() throws Exception {
        Path source = Files.createDirectory(directory.resolve("old"));
        Path partition = Files.createDirectories(source.resolve("orders/partition-0"));
        Path first = partition.resolve("000000000000.log");
        Path second = partition.resolve("000000000002.log");
        try (var output = new DataOutputStream(Files.newOutputStream(first))) {
            output.writeInt(1); output.writeByte(7); output.writeInt(0);
        }
        try (var output = new DataOutputStream(Files.newOutputStream(second))) {
            output.writeInt(2); output.write(new byte[]{8, 9});
        }
        byte[] firstBytes = Files.readAllBytes(first), secondBytes = Files.readAllBytes(second);
        Path target = directory.resolve("new");
        assertEquals(3, LegacyLogMigration.migrate(source, target, 1024));
        assertArrayEquals(firstBytes, Files.readAllBytes(first));
        assertArrayEquals(secondBytes, Files.readAllBytes(second));
        try (var log = new ChronicleMessageLog(target.resolve("orders/partition-0"), 1024, 64L << 20)) {
            var records = log.read(0, 3);
            assertEquals(3, records.size());
            assertArrayEquals(new byte[]{7}, records.get(0).value());
            assertEquals(0, records.get(1).valueLength());
            assertArrayEquals(new byte[]{8, 9}, records.get(2).value());
            assertEquals(3, log.append(new byte[]{10}));
        }
        assertThrows(IOException.class, () -> LegacyLogMigration.migrate(source, target, 1024));
        assertThrows(IOException.class, () -> LegacyLogMigration.migrate(source, source.resolve("nested"), 1024));
    }

    @Test void incompleteSourcesFailWithoutTruncatingOriginalFiles() throws Exception {
        Path source = Files.createDirectory(directory.resolve("old"));
        Path partition = Files.createDirectories(source.resolve("orders/partition-0"));
        Path file = partition.resolve("000000000000.log");
        byte[] bytes = new byte[]{0, 0, 0, 5, 7};
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> LegacyLogMigration.migrate(source, directory.resolve("new"), 1024));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }
}
