package io.setu.storage.log;

import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChronicleChecksumTests {
    @TempDir Path directory;

    @Test void payloadCorruptionFailsRecoveryAndReleasesOwnership() throws Exception {
        try (var queue = SingleChronicleQueueBuilder.binary(directory).rollCycle(RollCycles.FAST_DAILY)
                .syncMode(SyncMode.NONE).build(); var appender = queue.createAppender()) {
            try (var document = appender.writingDocument()) {
                document.wire().bytes().writeLong(0x5345545500000002L).writeLong(0).writeInt(1)
                        .writeInt(0).writeInt(1).writeByte((byte) 7);
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            var failure = assertThrows(IOException.class,
                    () -> new ChronicleMessageLog(directory, 1024, 64L << 20));
            assertTrue(failure.getMessage().contains("checksum"));
        }
    }

    @Test void legacyRecordsRemainReadableAlongsideChecksummedAppends() throws Exception {
        try (var queue = SingleChronicleQueueBuilder.binary(directory).rollCycle(RollCycles.FAST_DAILY)
                .syncMode(SyncMode.NONE).build(); var appender = queue.createAppender()) {
            try (var document = appender.writingDocument()) {
                document.wire().bytes().writeLong(0x5345545500000001L).writeLong(0).writeInt(1)
                        .writeInt(1).writeByte((byte) 9);
            }
        }
        try (var log = new ChronicleMessageLog(directory, 1024, 64L << 20)) {
            assertEquals(1, log.append(new byte[]{8}));
        }
        try (var log = new ChronicleMessageLog(directory, 1024, 64L << 20)) {
            assertArrayEquals(new byte[]{9}, log.read(0, 1).getFirst().value());
            assertArrayEquals(new byte[]{8}, log.read(1, 1).getFirst().value());
        }
    }
}
