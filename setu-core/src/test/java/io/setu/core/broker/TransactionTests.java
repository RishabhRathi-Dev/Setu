package io.setu.core.broker;

import io.setu.core.protocol.*;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTests {
    @TempDir Path directory;

    private BrokerRequest request(Command command, String topic, long offset, String member) {
        return new BrokerRequest(1, command, topic, 0, offset, 1024, new byte[]{7}, 1,
                member == null ? null : "group", member);
    }

    private void initialize(DefaultBroker broker) throws Exception {
        broker.execute(request(Command.CREATE_TOPIC, "input", 0, null));
        broker.execute(request(Command.CREATE_TOPIC, "output", 0, null));
        broker.execute(request(Command.JOIN_GROUP, "input", 0, "member"));
        broker.execute(request(Command.PRODUCE, "input", 0, null));
        broker.execute(request(Command.FETCH, "input", 0, "member"));
    }

    private BrokerRequest write(Command command, long epoch, long sequence, List<byte[]> values) {
        var commit = command == Command.TRANSACT ? new ConsumerCommit("group", "member", "input", 0, 1, -1) : null;
        return new BrokerRequest(1, command, "output", 0, -1, -1, null, 0, null, null, values)
                .withOptions(new RequestOptions(new ProducerIdentity("producer", epoch, sequence), commit,
                        AcknowledgementMode.DURABLE, -1));
    }

    @Test void deduplicationSequencesEpochFencingAndPayloadIdentitySurviveRestart() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            initialize(broker);
            assertEquals(0, broker.execute(write(Command.PRODUCE_IDEMPOTENT, 0, 0, List.of(new byte[]{1}))).offset());
            assertEquals(0, broker.execute(write(Command.PRODUCE_IDEMPOTENT, 0, 0, List.of(new byte[]{1}))).offset());
            assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BrokerException.class,
                    () -> broker.execute(write(Command.PRODUCE_IDEMPOTENT, 0, 0, List.of(new byte[]{2})))).errorCode());
            assertEquals(ErrorCode.OUT_OF_ORDER_SEQUENCE, assertThrows(BrokerException.class,
                    () -> broker.execute(write(Command.PRODUCE_IDEMPOTENT, 0, 2, List.of(new byte[]{2})))).errorCode());
            assertEquals(1, broker.execute(write(Command.PRODUCE_IDEMPOTENT, 1, 0, List.of(new byte[]{3}))).offset());
            assertEquals(ErrorCode.PRODUCER_FENCED, assertThrows(BrokerException.class,
                    () -> broker.execute(write(Command.PRODUCE_IDEMPOTENT, 0, 1, List.of(new byte[]{2})))).errorCode());
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertEquals(1, broker.execute(write(Command.PRODUCE_IDEMPOTENT, 1, 0, List.of(new byte[]{3}))).offset());
            assertEquals(2, broker.execute(request(Command.FETCH, "output", 0, null)).records().size());
        }
    }

    @Test void consumeProduceCommitIsAtomicAndRetryDoesNotRepeatOutputs() throws Exception {
        var transaction = write(Command.TRANSACT, 0, 0, List.of(new byte[]{1}, new byte[]{2}));
        try (var broker = new DefaultBroker(directory, 1024)) {
            initialize(broker);
            assertEquals(0, broker.execute(transaction).offset());
            assertEquals(1, broker.execute(request(Command.FETCH_OFFSET, "input", 0, "member")).offset());
            assertEquals(0, broker.execute(transaction).offset());
            var repeatedInput = transaction.withOptions(new RequestOptions(new ProducerIdentity("other", 0, 0),
                    transaction.options().commit(), AcknowledgementMode.DURABLE, -1));
            assertThrows(BrokerException.class, () -> broker.execute(repeatedInput));
        }
        try (var broker = new DefaultBroker(directory, 1024)) {
            assertEquals(0, broker.execute(transaction).offset());
            assertEquals(2, broker.execute(request(Command.FETCH, "output", 0, null)).records().size());
            assertEquals(1, broker.execute(request(Command.FETCH_OFFSET, "input", 0, "member")).offset());
        }
    }

    @Test void rejectedInputDoesNotAppendOutputOrConsumeProducerSequence() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            initialize(broker);
            var transaction = write(Command.TRANSACT, 0, 0, List.of(new byte[]{1}));
            var invalid = transaction.withOptions(new RequestOptions(transaction.options().producer(),
                    new ConsumerCommit("group", "member", "input", 0, 2, -1), AcknowledgementMode.DURABLE, -1));
            assertThrows(BrokerException.class, () -> broker.execute(invalid));
            assertTrue(broker.execute(request(Command.FETCH, "output", 0, null)).records().isEmpty());
            assertEquals(0, broker.execute(transaction).offset());
        }
    }

    @Test void recoveryCompletesJournaledTransactionAfterMissingOrPartialOutput() throws Exception {
        for (boolean partial : new boolean[]{false, true}) {
            Path data = directory.resolve(Boolean.toString(partial));
            var transaction = write(Command.TRANSACT, 0, 0, List.of(new byte[]{7}, new byte[]{8}));
            try (var broker = new DefaultBroker(data, 1024)) {
                initialize(broker);
                if (partial) broker.execute(request(Command.PRODUCE, "output", 0, null));
            }
            byte[] encoded = ProtocolCodec.encodeRequest(transaction);
            try (var journal = MessageLogs.open(StorageMode.FILE, data.resolve("@consumer-state"), 15 * 1024 * 1024)) {
                journal.append(ByteBuffer.allocate(12 + encoded.length).putInt(0x53545831).putLong(0).put(encoded).array());
            }
            try (var broker = new DefaultBroker(data, 1024)) {
                var records = broker.execute(request(Command.FETCH, "output", 0, null)).records();
                assertEquals(2, records.size());
                assertArrayEquals(new byte[]{8}, records.getLast().value());
                assertEquals(1, broker.execute(request(Command.FETCH_OFFSET, "input", 0, "member")).offset());
                assertEquals(0, broker.execute(transaction).offset());
            }
        }
    }

    @Test void longPollDoesNotHoldTransactionGateAndAcceptedWritesDrainBeforeTransaction() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            initialize(broker);
            var poll = workers.submit(() -> broker.execute(request(Command.FETCH, "output", 0, null).withWaitMillis(5000)));
            var accepted = new BrokerRequest(1, Command.PRODUCE_CONFIGURED, "output", 0, -1, -1,
                    null, 0, null, null, List.of(new byte[]{9}))
                    .withOptions(new RequestOptions(null, null, AcknowledgementMode.ACCEPTED, -1));
            assertEquals(-1, broker.execute(accepted).offset());
            assertEquals(1, broker.execute(write(Command.TRANSACT, 0, 0, List.of(new byte[]{1}))).offset());
            assertFalse(poll.get(3, TimeUnit.SECONDS).records().isEmpty());
        }
    }

    @Test void journaledTransactionWithConflictingOutputFailsStartup() throws Exception {
        try (var broker = new DefaultBroker(directory, 1024)) {
            initialize(broker);
            broker.execute(request(Command.PRODUCE, "output", 0, null));
        }
        byte[] encoded = ProtocolCodec.encodeRequest(write(Command.TRANSACT, 0, 0, List.of(new byte[]{8})));
        try (var journal = MessageLogs.open(StorageMode.FILE, directory.resolve("@consumer-state"), 15 * 1024 * 1024)) {
            journal.append(ByteBuffer.allocate(12 + encoded.length).putInt(0x53545831).putLong(0).put(encoded).array());
        }
        assertThrows(java.io.IOException.class, () -> new DefaultBroker(directory, 1024));
        assertThrows(java.io.IOException.class, () -> new DefaultBroker(directory, 1024));
    }
}
