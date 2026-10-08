package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedFileMessageLogTests {
    @TempDir Path tempDir;

    @Test
    void rotatesAtConfiguredSizeAndRecoversOffsetsAcrossSegments() throws Exception {
        try (SegmentedFileMessageLog log = new SegmentedFileMessageLog(tempDir, 1024, 10)) {
            assertEquals(0, log.append("abc".getBytes()));
            assertEquals(1, log.append("def".getBytes()));
            assertTrue(Files.exists(tempDir.resolve("000000000000.log")));
            assertTrue(Files.exists(tempDir.resolve("000000000001.log")));
            assertEquals(1, log.read(1, 1024).getFirst().offset());
        }
        Files.write(tempDir.resolve("000000000001.log"), new byte[] { 0, 0 }, StandardOpenOption.APPEND);

        try (SegmentedFileMessageLog recovered = new SegmentedFileMessageLog(tempDir, 1024, 10)) {
            assertEquals(2, recovered.nextOffset());
            var records = recovered.read(0, 1024);
            assertEquals(2, records.size());
            assertEquals(0, records.getFirst().offset());
            assertArrayEquals("abc".getBytes(), records.getFirst().value());
            assertEquals(1, records.getLast().offset());
            assertArrayEquals("def".getBytes(), records.getLast().value());
            assertEquals(2, recovered.append("ghi".getBytes()));
        }

        assertTrue(Files.exists(tempDir.resolve("000000000002.log")));
    }
}
