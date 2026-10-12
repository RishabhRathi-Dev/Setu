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
