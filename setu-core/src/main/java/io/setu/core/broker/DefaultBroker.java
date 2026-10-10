package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.ProtocolRecord;
import io.setu.storage.log.MessageLog;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Single-process broker with selectable in-memory or segmented file partition logs. */
public final class DefaultBroker implements Broker {
    private static final int MAX_TOPICS = 10_000;
    private static final int MAX_PARTITIONS_PER_TOPIC = 1_024;
    public static final long DEFAULT_ACK_TIMEOUT_MILLIS = 30_000;
    public static final long DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS = 60_000;
    private static final long CONSUMER_EXPIRATION_SCAN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private final Path dataDirectory;
    private final int maxRecordBytes;
    private final StorageMode storageMode;
    private final long maxSegmentBytes;
    private final long ackTimeoutNanos;
    private final long consumerInactivityTimeoutNanos;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, Long> committedOffsets = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> groupMembers = new ConcurrentHashMap<>();
    private final Map<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private volatile long nextConsumerExpirationScan;
    private volatile boolean closed;

    public DefaultBroker(Path dataDirectory, int maxRecordBytes) throws IOException {
        this(StorageMode.FILE, dataDirectory, maxRecordBytes,
                DEFAULT_ACK_TIMEOUT_MILLIS, DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS);
    }

    /** Creates an in-memory broker. All message data is discarded when it closes. */
    public DefaultBroker(int maxRecordBytes) throws IOException {
        this(StorageMode.IN_MEMORY, null, maxRecordBytes,
                DEFAULT_ACK_TIMEOUT_MILLIS, DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS);
    }

    /** Creates a broker using the selected partition-log implementation. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes,
                DEFAULT_ACK_TIMEOUT_MILLIS, DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS);
    }

    /**
     * Creates a broker with explicit acknowledgement and consumer inactivity deadlines.
     * Expired members are removed on broker traffic; no background timer is required.
     */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long ackTimeoutMillis, long consumerInactivityTimeoutMillis) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, MessageLogs.DEFAULT_MAX_SEGMENT_BYTES,
                ackTimeoutMillis, consumerInactivityTimeoutMillis);
    }

    /** Creates a broker with explicit file segment size and consumer deadlines. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long maxSegmentBytes, long ackTimeoutMillis,
                         long consumerInactivityTimeoutMillis) throws IOException {
        this.storageMode = Objects.requireNonNull(storageMode, "storageMode");
        if (storageMode == StorageMode.FILE && dataDirectory == null)
            throw new IllegalArgumentException("dataDirectory is required for file storage");
        this.dataDirectory = dataDirectory == null ? null : dataDirectory.toAbsolutePath().normalize();
        if (maxRecordBytes < 0 || maxRecordBytes > 15 * 1024 * 1024)
            throw new IllegalArgumentException("maxRecordBytes must be between 0 and 15 MiB");
        if (maxSegmentBytes <= 0) throw new IllegalArgumentException("maxSegmentBytes must be positive");
        if (ackTimeoutMillis <= 0 || consumerInactivityTimeoutMillis <= 0)
            throw new IllegalArgumentException("consumer timeouts must be positive");
        this.maxRecordBytes = maxRecordBytes;
        this.maxSegmentBytes = maxSegmentBytes;
        this.ackTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(ackTimeoutMillis);
        this.consumerInactivityTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(consumerInactivityTimeoutMillis);
        if (storageMode == StorageMode.FILE) {
            Files.createDirectories(this.dataDirectory);
            recoverTopics();
        }
    }

    @Override
    public BrokerResponse execute(BrokerRequest request) throws IOException {
        ensureOpen();
        expireConsumers(System.nanoTime());
        return switch (request.command()) {
            case CREATE_TOPIC -> createTopic(request);
            case PRODUCE -> produce(request);
            case PRODUCE_BATCH -> produceBatch(request);
            case FETCH -> fetch(request);
            case METADATA -> metadata(request);
            case COMMIT_OFFSET -> commitOffset(request);
            case JOIN_GROUP -> joinGroup(request);
            case LEAVE_GROUP -> leaveGroup(request);
            case HEARTBEAT -> heartbeat(request);
        };
    }

    private synchronized BrokerResponse createTopic(BrokerRequest request) throws IOException {
        validateTopicName(request.topic());
        if (topics.containsKey(request.topic())) {
            throw new BrokerException(ErrorCode.TOPIC_ALREADY_EXISTS, "Topic already exists: " + request.topic());
        }
        if (request.partitionCount() < 1 || request.partitionCount() > MAX_PARTITIONS_PER_TOPIC)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Partition count must be between 1 and " + MAX_PARTITIONS_PER_TOPIC);
        if (topics.size() >= MAX_TOPICS)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Broker topic limit reached");
        List<MessageLog> partitions = new ArrayList<>(request.partitionCount());
        Path topicPath = storageMode == StorageMode.FILE ? dataDirectory.resolve(request.topic()) : null;
        try {
            if (storageMode == StorageMode.FILE) Files.createDirectories(topicPath);
            for (int i = 0; i < request.partitionCount(); i++) {
                Path file = null;
                if (storageMode == StorageMode.FILE) {
                    Path partitionDir = topicPath.resolve("partition-" + i);
                    Files.createDirectories(partitionDir);
                    file = partitionDir.resolve("000000000000.log");
                }
                partitions.add(MessageLogs.open(storageMode, file, maxRecordBytes, maxSegmentBytes));
            }
            topics.put(request.topic(), new TopicState(List.copyOf(partitions)));
        } catch (IOException | RuntimeException failure) {
            closeLogs(partitions);
            throw failure;
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse produce(BrokerRequest request) throws IOException {
        if (request.value().length > maxRecordBytes)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Record exceeds configured maximum size");
        MessageLog log = partition(request.topic(), request.partition());
        long offset = log.append(request.value());
        if (storageMode == StorageMode.IN_MEMORY) reclaimConsumed(request.topic(), request.partition());
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", offset, List.of(), Map.of());
    }

    private BrokerResponse produceBatch(BrokerRequest request) throws IOException {
        List<byte[]> values = request.values();
        if (values.isEmpty()) throw new BrokerException(ErrorCode.BAD_REQUEST, "Produce batch must not be empty");
        for (byte[] value : values) {
            if (value.length > maxRecordBytes)
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Record exceeds configured maximum size");
        }
        MessageLog log = partition(request.topic(), request.partition());
        long firstOffset = log.appendBatch(values);
        if (storageMode == StorageMode.IN_MEMORY) reclaimConsumed(request.topic(), request.partition());
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", firstOffset,
                List.of(), Map.of());
    }

    private BrokerResponse fetch(BrokerRequest request) throws IOException {
        MessageLog log = partition(request.topic(), request.partition());
        if (request.offset() > log.nextOffset()) {
            throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE, "Offset is beyond the partition end");
        }
        long startOffset = request.offset();
        ConsumerState consumer = null;
        if (request.groupId() != null) {
            consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
            if (consumer == null || !consumer.topic.equals(request.topic()))
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered for this topic");
            if (storageMode == StorageMode.IN_MEMORY)
                startOffset = Math.max(startOffset, consumer.nextOffsets.getOrDefault(request.partition(), log.nextOffset()));
        }
        List<ProtocolRecord> records = log.read(startOffset, request.maxBytes()).stream()
                .map(record -> new ProtocolRecord(record.offset(), record.value())).toList();
        if (consumer != null && !records.isEmpty())
            consumer.recordFetch(request.partition(), records.get(records.size() - 1).offset() + 1,
                    System.nanoTime());
        else if (consumer != null) consumer.touch(System.nanoTime());
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", -1, records, Map.of());
    }

    private BrokerResponse metadata(BrokerRequest request) {
        Map<String, Integer> result = topics.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().partitions.size()));
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", -1, List.of(), result);
    }

    private synchronized BrokerResponse commitOffset(BrokerRequest request) throws IOException {
        MessageLog log = partition(request.topic(), request.partition());
        if (request.offset() > log.nextOffset()) {
            throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE, "Committed offset is beyond the partition end");
        }
        if (request.memberId() != null) {
            ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
            if (consumer == null || !consumer.topic.equals(request.topic()))
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered for this topic");
            long previous = consumer.nextOffsets.getOrDefault(request.partition(), log.nextOffset());
            if (request.offset() < previous)
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Acknowledged offset cannot move backwards");
            long fetched = consumer.fetchedOffsets.getOrDefault(request.partition(), previous);
            if (request.offset() > fetched)
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Cannot acknowledge records that were not fetched");
            consumer.nextOffsets.put(request.partition(), request.offset());
            consumer.acknowledged(request.partition(), request.offset(), previous, System.nanoTime());
            reclaimConsumed(request.topic(), request.partition());
        }
        committedOffsets.put(offsetKey(request.groupId(), request.topic(), request.partition()), request.offset());
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private synchronized BrokerResponse joinGroup(BrokerRequest request) {
        if (!topics.containsKey(request.topic())) {
            throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + request.topic());
        }
        Map<String, String> members = groupMembers.computeIfAbsent(request.groupId(), ignored -> new ConcurrentHashMap<>());
        String existing = members.putIfAbsent(request.memberId(), request.topic());
        if (existing != null && !existing.equals(request.topic())) {
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Member is already registered for a different topic");
        }
        ConsumerKey key = new ConsumerKey(request.groupId(), request.memberId());
        if (existing == null) {
            Map<Integer, Long> offsets = new ConcurrentHashMap<>();
            Map<Integer, Long> fetchedOffsets = new ConcurrentHashMap<>();
            TopicState topic = topics.get(request.topic());
            for (int i = 0; i < topic.partitions.size(); i++) {
                long initialOffset = storageMode == StorageMode.IN_MEMORY ? topic.partitions.get(i).nextOffset() : 0L;
                offsets.put(i, initialOffset);
                fetchedOffsets.put(i, initialOffset);
            }
            consumers.put(key, new ConsumerState(request.topic(), offsets, fetchedOffsets));
            consumers.get(key).touch(System.nanoTime());
            reclaimTopic(request.topic());
        } else {
            ConsumerState consumer = consumers.get(key);
            if (consumer != null) consumer.touch(System.nanoTime());
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private synchronized BrokerResponse leaveGroup(BrokerRequest request) {
        Map<String, String> members = groupMembers.get(request.groupId());
        String topic = members == null ? null : members.remove(request.memberId());
        ConsumerState removed = consumers.remove(new ConsumerKey(request.groupId(), request.memberId()));
        if (topic == null && removed != null) topic = removed.topic;
        if (topic != null) reclaimTopic(topic);
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse heartbeat(BrokerRequest request) {
        Map<String, String> members = groupMembers.get(request.groupId());
        if (members == null || !members.containsKey(request.memberId())) {
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered");
        }
        ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (consumer != null) consumer.touch(System.nanoTime());
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private void expireConsumers(long now) {
        if (now < nextConsumerExpirationScan) return;
        synchronized (this) {
            if (now < nextConsumerExpirationScan) return;
            nextConsumerExpirationScan = now + CONSUMER_EXPIRATION_SCAN_INTERVAL_NANOS;
            List<String> changedTopics = new ArrayList<>();
            for (Map.Entry<ConsumerKey, ConsumerState> entry : consumers.entrySet()) {
                ConsumerState consumer = entry.getValue();
                if (!consumer.expired(now, ackTimeoutNanos, consumerInactivityTimeoutNanos)) continue;
                if (!consumers.remove(entry.getKey(), consumer)) continue;
                Map<String, String> members = groupMembers.get(entry.getKey().groupId);
                if (members != null) {
                    members.remove(entry.getKey().memberId);
                    if (members.isEmpty()) groupMembers.remove(entry.getKey().groupId, members);
                }
                changedTopics.add(consumer.topic);
            }
            changedTopics.stream().distinct().forEach(this::reclaimTopic);
        }
    }

    private synchronized void reclaimConsumed(String topic, int partition) throws IOException {
        if (storageMode != StorageMode.IN_MEMORY) return;
        MessageLog log = partition(topic, partition);
        long lowestReadOffset = log.nextOffset();
        for (ConsumerState consumer : consumers.values()) {
            if (consumer.topic.equals(topic))
                lowestReadOffset = Math.min(lowestReadOffset,
                        consumer.nextOffsets.getOrDefault(partition, log.nextOffset()));
        }
        log.discardBefore(lowestReadOffset);
    }

    private synchronized void reclaimTopic(String topic) {
        TopicState state = topics.get(topic);
        if (state == null || storageMode != StorageMode.IN_MEMORY) return;
        for (int partition = 0; partition < state.partitions.size(); partition++) {
            try { reclaimConsumed(topic, partition); }
            catch (IOException e) { throw new IllegalStateException("Unable to reclaim in-memory records", e); }
        }
    }

    private MessageLog partition(String topic, int partition) {
        TopicState state = topics.get(topic);
        if (state == null) throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + topic);
        if (partition < 0 || partition >= state.partitions.size())
            throw new BrokerException(ErrorCode.UNKNOWN_PARTITION, "Unknown partition " + partition + " for " + topic);
        return state.partitions.get(partition);
    }

    private void recoverTopics() throws IOException {
        if (storageMode == StorageMode.IN_MEMORY) return;
        try (var paths = Files.list(dataDirectory)) {
            for (Path topicPath : paths.filter(Files::isDirectory).sorted().toList()) {
                String name = topicPath.getFileName().toString();
                if (!name.matches("[A-Za-z0-9._-]{1,249}")) continue;
                List<Path> partitionPaths;
                try (var children = Files.list(topicPath)) {
                    partitionPaths = children.filter(Files::isDirectory)
                            .filter(p -> p.getFileName().toString().matches("partition-[0-9]+"))
                            .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().substring(10))))
                            .toList();
                }
                if (partitionPaths.size() > MAX_PARTITIONS_PER_TOPIC)
                    throw new IOException("Topic exceeds partition limit: " + name);
                if (partitionPaths.isEmpty()) continue;
                if (topics.size() >= MAX_TOPICS)
                    throw new IOException("Broker exceeds topic limit while recovering data");
                List<MessageLog> logs = new ArrayList<>(partitionPaths.size());
                try {
                    for (int i = 0; i < partitionPaths.size(); i++) {
                        int partitionId = Integer.parseInt(partitionPaths.get(i).getFileName().toString().substring(10));
                        if (partitionId != i) throw new IOException("Partition directories must be contiguous for topic " + name);
                        logs.add(MessageLogs.open(storageMode,
                                partitionPaths.get(i).resolve("000000000000.log"), maxRecordBytes, maxSegmentBytes));
                    }
                    topics.put(name, new TopicState(List.copyOf(logs)));
                } catch (IOException | RuntimeException failure) {
                    closeLogs(logs);
                    throw failure;
                }
            }
        }
    }

    private static String offsetKey(String group, String topic, int partition) {
        return group + '\0' + topic + '\0' + partition;
    }

    private static void validateTopicName(String topic) {
        if (topic == null || topic.equals(".") || topic.equals("..")
                || !topic.matches("[A-Za-z0-9._-]{1,249}"))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Invalid topic name");
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Broker is closed");
    }

    private static void closeLogs(List<MessageLog> logs) {
        for (MessageLog log : logs) try { log.close(); } catch (IOException ignored) { }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (TopicState state : topics.values()) {
            for (MessageLog log : state.partitions) {
                try { log.close(); }
                catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private record TopicState(List<MessageLog> partitions) { }
    private record ConsumerKey(String groupId, String memberId) { }
    private static final class ConsumerState {
        private final String topic;
        private final Map<Integer, Long> nextOffsets;
        private final Map<Integer, Long> fetchedOffsets;
        private final Map<Integer, Long> pendingAckSince = new ConcurrentHashMap<>();
        private volatile long lastActivityNanos;

        private ConsumerState(String topic, Map<Integer, Long> nextOffsets, Map<Integer, Long> fetchedOffsets) {
            this.topic = topic;
            this.nextOffsets = nextOffsets;
            this.fetchedOffsets = fetchedOffsets;
        }

        private void touch(long now) { lastActivityNanos = now; }

        private void recordFetch(int partition, long nextFetchedOffset, long now) {
            long fetched = fetchedOffsets.merge(partition, nextFetchedOffset, Math::max);
            if (fetched > nextOffsets.getOrDefault(partition, 0L)) pendingAckSince.putIfAbsent(partition, now);
            touch(now);
        }

        private void acknowledged(int partition, long nextOffset, long previousOffset, long now) {
            if (nextOffset >= fetchedOffsets.getOrDefault(partition, 0L)) pendingAckSince.remove(partition);
            else if (nextOffset > previousOffset) pendingAckSince.put(partition, now);
            touch(now);
        }

        private boolean expired(long now, long ackTimeoutNanos, long inactivityTimeoutNanos) {
            if (now - lastActivityNanos >= inactivityTimeoutNanos) return true;
            return pendingAckSince.values().stream().anyMatch(since -> now - since >= ackTimeoutNanos);
        }
    }
}
