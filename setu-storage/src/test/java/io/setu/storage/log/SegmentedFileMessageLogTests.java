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
    void pagesAcrossSegmentsFromArbitraryOffsetsAndPreservesSnapshots() throws Exception {
        var values = new java.util.ArrayList<byte[]>();
        for (int i = 0; i < 64; i++) values.add(new byte[]{(byte) i, 1, 2, 3});
        try (var log = new SegmentedFileMessageLog(tempDir, 4, 16)) {
            assertEquals(0, log.appendBatch(values));
            assertEquals(64, log.nextOffset());
            for (int offset : new int[]{0, 1, 31, 32, 62, 63}) {
                var page = log.read(offset, 12);
                assertEquals(Math.min(3, 64 - offset), page.size());
                for (int i = 0; i < page.size(); i++) {
                    assertEquals(offset + i, page.get(i).offset());
                    assertArrayEquals(values.get(offset + i), page.get(i).value());
                }
                page.getFirst().value()[0] = 99;
                assertArrayEquals(values.get(offset), log.read(offset, 4).getFirst().value());
            }
            assertTrue(log.read(64, 12).isEmpty());
            assertTrue(log.read(20, 3).isEmpty());
        }
        try (var log = new SegmentedFileMessageLog(tempDir, 4, 16)) {
            assertEquals(31, log.read(31, 12).getFirst().offset());
            assertArrayEquals(values.getLast(), log.read(63, 4).getFirst().value());
        }
    }

    @Test
    void capsFetchRecordCountAcrossSegmentsEvenForEmptyPayloads() throws Exception {
        try (var log = new SegmentedFileMessageLog(tempDir, 0, 4096)) {
            log.appendBatch(java.util.Collections.nCopies(10_001, new byte[0]));
            assertEquals(10_000, log.read(0, 1).size());
            var last = log.read(10_000, 1);
            assertEquals(1, last.size());
            assertEquals(10_000, last.getFirst().offset());
        }
    }

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
