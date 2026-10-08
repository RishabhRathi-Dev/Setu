package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ProtocolRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultBrokerTests {
    @TempDir Path tempDir;

    @Test
    void topicsAndRecordsSurviveBrokerRestart() throws Exception {
        try (DefaultBroker broker = new DefaultBroker(tempDir, 1024)) {
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1,
                    null, 2, null, null));
            BrokerResponse written = broker.execute(new BrokerRequest(2, Command.PRODUCE, "orders", 1,
                    -1, -1, "order-1".getBytes(), 0, null, null));
            assertEquals(0, written.offset());
        }
        try (DefaultBroker recovered = new DefaultBroker(tempDir, 1024)) {
            BrokerResponse response = recovered.execute(new BrokerRequest(3, Command.FETCH, "orders", 1,
                    0, 1024, null, 0, null, null));
            assertEquals(1, response.records().size());
            ProtocolRecord record = response.records().getFirst();
            assertEquals(0, record.offset());
            assertArrayEquals("order-1".getBytes(), record.value());
        }
    }
}
