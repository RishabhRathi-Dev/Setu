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
