package io.setu.server;

import io.setu.core.protocol.BufferBudget;
import io.setu.core.protocol.RequestBuffers;
import io.setu.server.dispatcher.RequestHandler;
import io.setu.server.dispatcher.RequestProcessor;
import io.setu.storage.log.MessageValue;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import static org.junit.jupiter.api.Assertions.*;

class OwnedFrameDispatchTests {
    @Test void legacyHandlersReceivePrivateCopiesAndReserveBeforeAllocatingThem() throws Exception {
        var frame = MessageValue.Frame.readFrom(new DataInputStream(new ByteArrayInputStream(new byte[]{1, 2})), 2);
        RequestHandler handler = bytes -> { bytes[0] = 9; return bytes; };
        assertArrayEquals(new byte[]{9, 2}, RequestProcessor.plaintext(handler).process(null, frame));
        assertArrayEquals(new byte[]{1, 2}, frame.copy());
        RequestProcessor processor = (identity, bytes) -> { bytes[0] = 8; return bytes; };
        assertArrayEquals(new byte[]{8, 2}, processor.process(null, frame));
        assertArrayEquals(new byte[]{1, 2}, frame.copy());
        var budget = new BufferBudget(3);
        try (var credit = budget.reserve(2); var context = RequestBuffers.activate(credit)) {
            assertThrows(BufferBudget.Exhausted.class, () -> handler.handle(frame));
            assertThrows(BufferBudget.Exhausted.class, () -> processor.process(null, frame));
            assertEquals(2, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }
}
