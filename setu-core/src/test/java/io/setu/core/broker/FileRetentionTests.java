package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;
import io.setu.storage.log.StorageMode;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FileRetentionTests {
    @TempDir Path directory;

    private Path seedDailyFiles() throws Exception {
        Path partition = directory.resolve("orders/partition-0");
        var clock = new AtomicLong(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3));
        try (var queue = SingleChronicleQueueBuilder.binary(partition).rollCycle(RollCycles.FAST_DAILY)
                .timeProvider(clock::get).syncMode(SyncMode.NONE).build(); var appender = queue.createAppender()) {
            for (int i = 0; i < 2; i++) {
                // Version-one Setu document fixture, one logical record per daily cycle.
                try (var document = appender.writingDocument()) {
                    document.wire().bytes().writeLong(0x5345545500000001L).writeLong(i)
                            .writeInt(1).writeInt(1).writeByte((byte) i);
                }
                clock.addAndGet(TimeUnit.DAYS.toMillis(1));
            }
        }
        return partition;
    }

    private static BrokerRequest request(Command command, long offset, String member) {
        return new BrokerRequest(1, command, "orders", 0, offset, 1024,
                command == Command.PRODUCE ? new byte[]{2} : null, 0, member == null ? null : "g", member);
    }

    @Test void backgroundRetentionOnRecoveredPartitionsReportsGapsAndSurvivesRestart() throws Exception {
        Path partition = seedDailyFiles();
        try (var broker = new DefaultBroker(StorageMode.FILE, directory, 1024, 64L << 20,
                30_000, 60_000, 1024 * 1024, 0, 1)) {
            broker.execute(request(Command.JOIN_GROUP, 0, "slow"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(partition.resolve(".setu-retention")) && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertTrue(Files.exists(partition.resolve(".setu-retention")), "cleanup must run without requests or acknowledgements");
            for (String member : new String[]{null, "slow"}) {
                var gap = assertThrows(BrokerException.class, () -> broker.execute(request(Command.FETCH, 0, member)));
                assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE, gap.errorCode());
                assertTrue(gap.getMessage().contains("offset 1"));
            }
            assertEquals(1, broker.execute(request(Command.FETCH, 1, null)).records().getFirst().offset());
            assertEquals(2, broker.execute(request(Command.PRODUCE, 0, null)).offset());
        }
        try (var broker = new DefaultBroker(directory, 1024)) { // turning retention off cannot restore expired data
            assertEquals(ErrorCode.OFFSET_OUT_OF_RANGE,
                    assertThrows(BrokerException.class, () -> broker.execute(request(Command.FETCH, 0, null))).errorCode());
            assertEquals(2, broker.execute(request(Command.FETCH, 1, null)).records().size());
            assertEquals(3, broker.execute(request(Command.PRODUCE, 0, null)).offset());
        }
    }

    @Test void retentionIsDisabledByDefault() throws Exception {
        Path partition = seedDailyFiles();
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertEquals(2, broker.execute(request(Command.FETCH, 0, null)).records().size());
            assertFalse(Files.exists(partition.resolve(".setu-retention")));
        }
    }

    @Test void rejectsInvalidFileRetention() {
        for (long invalid : new long[]{-1, TimeUnit.DAYS.toMillis(365) + 1})
            assertThrows(IllegalArgumentException.class, () -> new DefaultBroker(StorageMode.FILE,
                    directory, 1024, 64L << 20, 30_000, 60_000, 0, 0, invalid));
    }
}
