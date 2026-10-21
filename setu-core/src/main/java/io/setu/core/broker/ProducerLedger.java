package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.ErrorCode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

final class ProducerLedger {
    private final Map<Key, Entry> entries = new HashMap<>();

    Long duplicate(BrokerRequest request, byte[] fingerprint) {
        var producer = request.options().producer();
        var previous = entries.get(new Key(producer.id(), request.topic(), request.partition()));
        if (previous == null) {
            if (entries.size() >= 10_000)
                throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, "Producer partition limit reached");
            if (producer.sequence() != 0) throw sequence();
            return null;
        }
        if (producer.epoch() < previous.epoch)
            throw new BrokerException(ErrorCode.PRODUCER_FENCED, "Producer epoch has been fenced");
        if (producer.epoch() > previous.epoch) {
            if (producer.sequence() != 0) throw sequence();
            return null;
        }
        if (producer.sequence() == previous.sequence) {
            if (!MessageDigest.isEqual(previous.fingerprint, fingerprint))
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Producer sequence was reused for different work");
            return previous.offset;
        }
        if (previous.sequence == Long.MAX_VALUE || producer.sequence() != previous.sequence + 1) throw sequence();
        return null;
    }

    void record(BrokerRequest request, byte[] fingerprint, long offset) {
        var producer = request.options().producer();
        entries.put(new Key(producer.id(), request.topic(), request.partition()),
                new Entry(producer.epoch(), producer.sequence(), fingerprint, offset));
    }

    static byte[] fingerprint(byte[] canonicalRequest) {
        try { return MessageDigest.getInstance("SHA-256").digest(canonicalRequest); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static BrokerException sequence() {
        return new BrokerException(ErrorCode.OUT_OF_ORDER_SEQUENCE, "Producer sequences must start at zero and advance by one");
    }

    private record Key(String producer, String topic, int partition) { }
    private record Entry(long epoch, long sequence, byte[] fingerprint, long offset) { }
}
