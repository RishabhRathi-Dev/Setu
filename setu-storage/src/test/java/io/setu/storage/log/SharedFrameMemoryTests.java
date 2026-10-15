package io.setu.storage.log;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.channels.Channels;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedFrameMemoryTests {
    private static MessageValue.Frame frame(int size) throws Exception {
        byte[] source = new byte[size];
        java.util.Arrays.fill(source, (byte) 7);
        return MessageValue.Frame.readFrom(new DataInputStream(new ByteArrayInputStream(source)), size);
    }

    @Test void chargesFrameOnceAndKeepsItChargedUntilLastSliceIsDiscarded() throws Exception {
        var frame = frame(4096);
        var budget = new MemoryLogBudget(14_000);
        try (var log = new InMemoryMessageLog(1024, budget)) {
            log.appendValues(List.of(frame.valueAt(0, 1024), frame.valueAt(1024, 1024)));
            long two = budget.usedBytes();
            log.appendValues(List.of(frame.valueAt(2048, 1024), frame.valueAt(3072, 1024)));
            long four = budget.usedBytes();
            assertEquals(64, four - two, "another slice must not charge the whole frame again");
            var snapshot = log.read(0, 4096);
            log.discardBefore(3);
            assertEquals(four - 96, budget.usedBytes(), "the remaining slice retains the entire frame");
            assertArrayEquals(new byte[]{7, 7, 7}, java.util.Arrays.copyOf(log.read(3, 1024).getFirst().value(), 3));
            log.discardBefore(4);
            assertEquals(0, budget.usedBytes());
            assertEquals(7, snapshot.getFirst().value()[0], "reclamation cannot mutate a fetched snapshot");
        }
    }

    @Test void chargesSharedFramesSeparatelyAcrossChunksAndHandlesReorderedRuns() throws Exception {
        var first = frame(4096).valueAt(0, 1);
        var second = frame(4096).valueAt(0, 1);
        var budget = new MemoryLogBudget(0);
        try (var log = new InMemoryMessageLog(1, budget)) {
            log.appendValues(Collections.nCopies(1025, first));
            log.discardBefore(1024);
            long one = budget.usedBytes();
            assertTrue(one > 4096, "the second chunk still owns the full backing frame");
            log.appendValues(List.of(second, first));
            log.discardBefore(1026);
            assertEquals(one, budget.usedBytes());
            log.discardBefore(1027);
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void partialFetchReservesTheWholeFrameBeforeItCanOutliveLogReclamation() throws Exception {
        var frame = frame(4096);
        try (var log = new InMemoryMessageLog(1024)) {
            log.appendValues(List.of(frame.valueAt(0, 1024), frame.valueAt(1024, 1024),
                    frame.valueAt(2048, 1024), frame.valueAt(3072, 1024)));
            var reserved = new java.util.concurrent.atomic.AtomicLong();
            var records = log.read(0, 1024, reserved::set);
            assertTrue(reserved.get() >= 4096 + 1024, "account both the retained frame and encoded reply");
            log.discardBefore(4);
            assertEquals(7, records.getFirst().value()[0]);
            log.appendValues(List.of(frame.valueAt(0, 1024), frame.valueAt(1024, 1024)));
            log.read(4, 2048, reserved::set);
            assertTrue(reserved.get() < 2 * 4096, "sibling slices must share one frame reservation");
        }
    }

    @Test void incrementalReaderPermanentlyRemovesWritableAccessOnCompletion() throws Exception {
        var reader = new MessageValue.Frame.Reader(3);
        assertThrows(IllegalStateException.class, reader::finish);
        var channel = Channels.newChannel(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        assertEquals(3, reader.readFrom(channel));
        var frame = reader.finish();
        assertThrows(IllegalStateException.class, () -> reader.readFrom(channel));
        assertThrows(IllegalStateException.class, reader::finish);
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> frame.cursor().put(0, (byte) 9));
        byte[] copy = frame.copy();
        copy[0] = 9;
        assertEquals(1, frame.valueAt(0, 1).copy()[0]);
    }
}
