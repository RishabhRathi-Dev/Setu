package io.setu.core.protocol;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BufferAdmissionTests {
    @Test void emptyBatchItemsCannotBypassDecodedObjectAdmission() throws Exception {
        var batch = new BrokerRequest(1, Command.PRODUCE_BATCH, "orders", 0, -1, -1,
                null, 0, null, null, java.util.Collections.nCopies(100, new byte[0]));
        byte[] encoded = ProtocolCodec.encodeRequest(batch);
        var budget = new BufferBudget(1024);
        try (var credit = budget.reserve(100); var context = RequestBuffers.activate(credit)) {
            var rejected = assertThrows(ProtocolException.class, () -> ProtocolCodec.decodeRequest(encoded));
            assertEquals(ErrorCode.QUOTA_EXCEEDED, rejected.errorCode());
            assertEquals(100, budget.usedBytes());
        }
        assertEquals(0, budget.usedBytes());
    }
    @Test void concurrentReservationsAreBoundedAndCancellationReleasesExactlyOnce() throws Exception {
        var budget = new BufferBudget(100);
        try (var credit = budget.reserve(80)) {
            assertThrows(BufferBudget.Exhausted.class, () -> budget.reserve(21));
            try (var tail = budget.reserve(20)) { assertEquals(100, budget.usedBytes()); }
            credit.close(); credit.close();
            assertEquals(0, budget.usedBytes());
        }
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<Callable<Void>>();
            for (int i = 0; i < 64; i++) tasks.add(() -> {
                for (int j = 0; j < 100; j++) {
                    try (var credit = budget.reserve(50)) { assertTrue(budget.usedBytes() <= 100); }
                    catch (BufferBudget.Exhausted expected) { }
                }
                return null;
            });
            for (var task : workers.invokeAll(tasks)) task.get();
        }
        assertEquals(0, budget.usedBytes());
    }
    @Test void responseEncodingReservesBeforeAllocationAndScopeSurvivesWorkerContext() throws Exception {
        var budget = new BufferBudget(32);
        var response = new BrokerResponse(1, Command.FETCH, ErrorCode.NONE, "", -1,
                List.of(new ProtocolRecord(0, new byte[100])), Map.of());
        try (var scope = new ResponseBuffers(budget)) {
            try (var context = scope.activate()) {
                assertThrows(BufferBudget.Exhausted.class, () -> ProtocolCodec.encodeResponse(response));
                ProtocolCodec.encodeResponse(BrokerResponse.ok(1, Command.CREATE_TOPIC));
                assertEquals(18, budget.usedBytes());
            }
            assertEquals(18, budget.usedBytes(), "socket writer still owns the response after context exit");
        }
        assertEquals(0, budget.usedBytes());
    }
    @Test void longPollExtensionPreservesLegacyEncodingAndSupportsGroupedAndUngroupedFetch() throws Exception {
        for (String group : new String[]{null, "g"}) {
            var legacy = new BrokerRequest(1, Command.FETCH, "orders", 0, 0, 1024, null, 0, group, group == null ? null : "m");
            assertEquals(0, ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(legacy)).waitMillis());
            var decoded = ProtocolCodec.decodeRequest(ProtocolCodec.encodeRequest(legacy.withWaitMillis(250)));
            assertEquals(250, decoded.waitMillis());
            assertEquals(group, decoded.groupId());
            assertEquals(legacy.memberId(), decoded.memberId());
        }
    }
}
