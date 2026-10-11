package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageLayoutTests {
    @TempDir Path directory;

    @Test
    void memoryReclamationKeepsOffsetsAndValuesAcrossPartialAndWholeChunks() throws Exception {
        byte[] value = new byte[513];
        Arrays.fill(value, (byte) 7);
        try (var log = new InMemoryMessageLog(513)) {
            log.appendBatch(Collections.nCopies(4000, value));
            value[0] = 99;
            log.discardBefore(499);
            var records = log.read(0, 513 * 602);
            assertEquals(602, records.size());
            assertEquals(499, records.getFirst().offset());
            assertEquals(1100, records.getLast().offset());
            assertEquals(7, records.getFirst().value()[0]);
            assertTrue(log.read(499, 512).isEmpty());
            log.discardBefore(2500);
            assertEquals(2500, log.read(0, 513).getFirst().offset());
            log.discardBefore(4000);
            assertTrue(log.read(0, 10000).isEmpty());
            assertEquals(4000, log.append(new byte[0]));
            assertEquals(4000, log.read(0, 1).getFirst().offset());
            assertEquals(4001, log.nextOffset());
        }
    }

    @Test
    void bothBackendsValidateBatchesBeforeAppendingAndBoundEmptyRecordReads() throws Exception {
        for (MessageLog log : List.of(new InMemoryMessageLog(1),
                new FileMessageLog(directory.resolve("empty.log"), 1))) {
            try (log) {
                assertThrows(java.io.IOException.class, () -> log.appendBatch(List.of(new byte[0], new byte[2])));
                assertEquals(0, log.nextOffset());
                log.appendBatch(Collections.nCopies(10_001, new byte[0]));
                assertTrue(log.read(0, 0).isEmpty());
                assertEquals(10_000, log.read(0, 1).size());
                assertEquals(10_000, log.read(10_000, 1).getFirst().offset());
            }
        }
    }

    @Test
    void fileReadsRespectPayloadBudgetsAcrossWindowsAndSurviveRestart() throws Exception {
        Path path = directory.resolve("windows.log");
        var values = new ArrayList<byte[]>();
        for (int i = 0; i < 5000; i++) {
            byte[] value = new byte[513];
            value[0] = (byte) i;
            values.add(value);
        }
        values.add(new byte[1024 * 1024 + 1]);
        values.add(new byte[0]);
        for (int open = 0; open < 2; open++) {
            try (var log = new FileMessageLog(path, 2 * 1024 * 1024)) {
                if (open == 0) assertEquals(0, log.appendBatch(values));
                assertEquals(values.size(), log.nextOffset());
                var records = log.read(0, 5000 * 513);
                assertEquals(5000, records.size());
                for (int i = 0; i < records.size(); i++) {
                    assertEquals(i, records.get(i).offset());
                    assertArrayEquals(values.get(i), records.get(i).value());
                }
                assertTrue(log.read(5000, 1024 * 1024).isEmpty());
                assertEquals(2, log.read(5000, 1024 * 1024 + 1).size());
                assertTrue(log.read(Long.MAX_VALUE, Integer.MAX_VALUE).isEmpty());
            }
        }
    }
}
