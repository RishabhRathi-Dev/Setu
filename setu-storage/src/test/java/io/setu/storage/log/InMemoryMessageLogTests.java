package io.setu.storage.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryMessageLogTests {
    @Test
    void reclaimsPrefixesWithoutRenumberingOffsets() throws Exception {
        try (InMemoryMessageLog log = new InMemoryMessageLog(128)) {
            assertEquals(0, log.append("zero".getBytes()));
            assertEquals(1, log.append("one".getBytes()));
            assertEquals(2, log.append("two".getBytes()));

            log.discardBefore(2);
            assertEquals(2, log.firstOffset());
            assertEquals(3, log.nextOffset());
            var remaining = log.read(0, 128);
            assertEquals(1, remaining.size());
            assertEquals(2, remaining.getFirst().offset());
            assertArrayEquals("two".getBytes(), remaining.getFirst().value());

            assertEquals(3, log.append("three".getBytes()));
            assertEquals(4, log.nextOffset());
        }
    }

    @Test
    void storesCopiesOfAppendedValues() throws Exception {
        byte[] payload = "original".getBytes();
        try (InMemoryMessageLog log = new InMemoryMessageLog(128)) {
            log.append(payload);
            payload[0] = 'X';
            byte[] returned = log.read(0, 128).getFirst().value();
            assertArrayEquals("original".getBytes(), returned);
            returned[0] = 'Y';
            assertArrayEquals("original".getBytes(), log.read(0, 128).getFirst().value());
        }
    }
}
