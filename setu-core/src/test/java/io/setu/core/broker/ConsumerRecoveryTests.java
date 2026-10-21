package io.setu.core.broker;

import io.setu.core.protocol.*;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerRecoveryTests {
    @TempDir Path directory;

    private BrokerRequest request(Command command) {
        return new BrokerRequest(0, command, "orders", 0, 0, 1024, null, 1, "group", "member");
    }

    @Test void expiredMembershipDoesNotReappearAfterRestart() throws Exception {
        try (var broker = new DefaultBroker(StorageMode.FILE, directory, 1024, 200, 200)) {
            broker.execute(request(Command.CREATE_TOPIC));
            broker.execute(request(Command.JOIN_GROUP));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (broker.activeConsumerCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, broker.activeConsumerCount());
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertEquals(0, broker.activeConsumerCount());
        }
    }

    @Test void malformedCommittedMetadataFailsStartupAndReleasesPartitionLocks() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            broker.execute(request(Command.CREATE_TOPIC));
            broker.execute(request(Command.JOIN_GROUP));
        }
        try (var journal = MessageLogs.open(StorageMode.FILE, directory.resolve("@consumer-state"), 16 * 1024)) {
            journal.append(ProtocolCodec.encodeRequest(request(Command.PRODUCE)));
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            var failure = assertThrows(IOException.class, () -> new DefaultBroker(directory, 1024));
            assertTrue(failure.getMessage().contains("consumer metadata"));
        }
    }

    @Test void offsetLookupUsesLongWireOffsetsAndRequiresAMember() throws Exception {
        var response = new BrokerResponse(0, Command.FETCH_OFFSET, ErrorCode.NONE, "", Long.MAX_VALUE,
                java.util.List.of(), java.util.Map.of());
        assertEquals(Long.MAX_VALUE, ProtocolCodec.decodeResponse(ProtocolCodec.encodeResponse(response)).offset());
        assertEquals(Command.FETCH_OFFSET,
                ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(request(Command.FETCH_OFFSET))).command());
        var invalid = new BrokerRequest(0, Command.FETCH_OFFSET, "orders", 0, 0, 0, null, 0, "group", null);
        assertThrows(ProtocolException.class, () -> ProtocolCodec.encodeRequest(invalid));
    }
}
