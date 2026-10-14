package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FileMessageLogTests {
    @TempDir Path tempDir;

    @Test
    void uniformOffsetsRemainCorrectWhenRecoveredLogTransitionsToVariableLengths() throws Exception {
        for (int length : new int[]{0, 3, 1024}) {
            Path path = tempDir.resolve("uniform-" + length + ".log");
            var expected = new java.util.ArrayList<byte[]>();
            for (int i = 0; i < 1537; i++) {
                byte[] value = new byte[length];
                java.util.Arrays.fill(value, (byte) i);
                expected.add(value);
            }
            try (var log = new FileMessageLog(path, 4096)) {
                assertEquals(0, log.appendBatch(expected));
                assertEquals(1536, log.read(1536, 4096).getFirst().offset());
                assertArrayEquals(expected.get(1536), log.read(1536, 4096).getFirst().value());
            }
            try (var recovered = new FileMessageLog(path, 4096)) {
                assertEquals(expected.size(), recovered.nextOffset());
                var varied = java.util.List.of(new byte[]{9}, new byte[0], new byte[2048]);
                assertEquals(1537, recovered.appendBatch(varied));
                expected.addAll(varied);
                assertEquals(1540, recovered.append(new byte[]{7, 8}));
                expected.add(new byte[]{7, 8});
                var actual = recovered.read(0, 4 * 1024 * 1024);
                assertEquals(expected.size(), actual.size());
                for (int i = 0; i < expected.size(); i++) {
                    assertEquals(i, actual.get(i).offset());
                    assertArrayEquals(expected.get(i), actual.get(i).value());
                }
            }
            try (var recovered = new FileMessageLog(path, 4096)) {
                assertEquals(expected.size(), recovered.nextOffset());
                var suffix = recovered.read(1535, 4096);
                for (int i = 0; i < suffix.size(); i++) assertArrayEquals(expected.get(1535 + i), suffix.get(i).value());
            }
        }
    }

    @Test
    void reusedAppendBufferPublishesOnlyCurrentBytesAcrossGrowthAndOversizedBatches() throws Exception {
        Path path = tempDir.resolve("reused.log");
        var expected = new java.util.ArrayList<byte[]>();
        for (int size : new int[]{0, 1, 4092, 4093, 262144, 7, 1048577, 3, 0}) {
            byte[] value = new byte[size];
            java.util.Arrays.fill(value, (byte) expected.size());
            expected.add(value);
        }
        long bytes = expected.stream().mapToLong(value -> 4L + value.length).sum();
        try (var log = new FileMessageLog(path, 2 * 1024 * 1024)) {
            for (int i = 0; i < expected.size(); i++) {
                if (i % 2 == 0) assertEquals(i, log.appendBatch(java.util.List.of(expected.get(i))));
                else assertEquals(i, log.append(expected.get(i)));
            }
            assertEquals(bytes, log.sizeBytes());
            assertEquals(bytes, Files.size(path));
        }
        try (var recovered = new FileMessageLog(path, 2 * 1024 * 1024)) {
            assertEquals(expected.size(), recovered.nextOffset());
            var actual = recovered.read(0, 4 * 1024 * 1024);
            for (int i = 0; i < expected.size(); i++) assertArrayEquals(expected.get(i), actual.get(i).value());
        }
    }

    @Test
    void windowSlicesSurviveLaterReadsAppendAndCloseWithoutExposingBackingBytes() throws Exception {
        var expected = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < 2500; i++) {
            byte[] value = new byte[1024];
            java.nio.ByteBuffer.wrap(value).putInt(i);
            expected.add(value);
        }
        java.util.List<LogRecord> snapshot;
        try (var log = new FileMessageLog(tempDir.resolve("windows.log"), 1024)) {
            log.appendBatch(expected);
            snapshot = log.read(17, 2200 * 1024);
            assertEquals(2200, snapshot.size());
            snapshot.getFirst().value()[0] = 99;
            log.append(new byte[]{7});
            assertEquals(1, log.read(2500, 1024).size());
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> log.read(0, 1024, bytes -> { throw new java.io.IOException("quota"); }));
        }
        for (int i = 0; i < snapshot.size(); i++) {
            assertEquals(i + 17, snapshot.get(i).offset());
            assertArrayEquals(expected.get(i + 17), snapshot.get(i).value());
        }
    }

    @Test
    void appendsReadsAndRecoversTheValidPrefixAfterATornTail() throws Exception {
        Path path = tempDir.resolve("partition.log");
        try (FileMessageLog log = new FileMessageLog(path, 1024)) {
            assertEquals(0, log.append("first".getBytes()));
            assertEquals(1, log.append("second".getBytes()));
            assertEquals(2, log.nextOffset());
        }

        Files.write(path, new byte[] { 0, 0 }, StandardOpenOption.APPEND);
        try (FileMessageLog recovered = new FileMessageLog(path, 1024)) {
            assertEquals(2, recovered.nextOffset());
            var records = recovered.read(0, 1024);
            assertEquals(2, records.size());
            assertArrayEquals("first".getBytes(), records.get(0).value());
            assertArrayEquals("second".getBytes(), records.get(1).value());
        }
        assertTrueNoPartialTail(path);
    }

    private static void assertTrueNoPartialTail(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(4 + 5 + 4 + 6, bytes.length);
    }
}
