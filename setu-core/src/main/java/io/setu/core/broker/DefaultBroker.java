package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.ProtocolRecord;
import io.setu.storage.log.MessageLog;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.StorageMode;
import io.setu.storage.log.MemoryLogBudget;
import io.setu.storage.log.LogCapacityExceededException;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single-process broker with selectable in-memory or Chronicle Queue partition logs. */
public final class DefaultBroker implements Broker {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultBroker.class);
    private static final int MAX_TOPICS = 10_000;
    private static final int MAX_PARTITIONS_PER_TOPIC = 1_024;
    public static final long DEFAULT_ACK_TIMEOUT_MILLIS = 30_000;
    public static final long DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS = 60_000;
    public static final long DEFAULT_MAX_RETAINED_MEMORY_BYTES = 256L * 1024 * 1024;
    private final Path dataDirectory;
    private final int maxRecordBytes;
    private final StorageMode storageMode;
    private final long queueBlockBytes;
    private final long ackTimeoutNanos;
    private final long consumerInactivityTimeoutNanos;
    private final MemoryLogBudget memoryBudget;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final ConsumerMetadataLimits consumerMetadataLimits;
    private int consumerPartitionMemberships;
    private final Map<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final java.util.concurrent.ScheduledExecutorService maintenance;
    private final long memoryRetentionNanos;

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
     * Background maintenance evicts members without putting expiration scans on the request path.
     */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long ackTimeoutMillis, long consumerInactivityTimeoutMillis) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, MessageLogs.DEFAULT_QUEUE_BLOCK_BYTES,
                ackTimeoutMillis, consumerInactivityTimeoutMillis);
    }

    /** Creates a broker with explicit Chronicle mapped block size and consumer deadlines. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis,
                         long consumerInactivityTimeoutMillis) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, queueBlockBytes, ackTimeoutMillis,
                consumerInactivityTimeoutMillis, DEFAULT_MAX_RETAINED_MEMORY_BYTES);
    }

    /** Shares one memory-log budget across all partitions; zero disables this limit. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis,
                         long consumerInactivityTimeoutMillis, long maxRetainedMemoryBytes) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, queueBlockBytes, ackTimeoutMillis,
                consumerInactivityTimeoutMillis, maxRetainedMemoryBytes, 0);
    }

    /** Positive retention enables lossy memory expiry even when a consumer never acknowledges. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis, long consumerInactivityTimeoutMillis,
                         long maxRetainedMemoryBytes, long memoryRetentionMillis) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, queueBlockBytes, ackTimeoutMillis,
                consumerInactivityTimeoutMillis, maxRetainedMemoryBytes, memoryRetentionMillis, 0);
    }

    /** File retention expires completed daily files regardless of consumer progress; zero disables it. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis, long consumerInactivityTimeoutMillis,
                         long maxRetainedMemoryBytes, long memoryRetentionMillis, long fileRetentionMillis) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, queueBlockBytes, ackTimeoutMillis,
                consumerInactivityTimeoutMillis, maxRetainedMemoryBytes, memoryRetentionMillis,
                fileRetentionMillis, ConsumerMetadataLimits.defaults());
    }

    /** Rejects new members atomically before allocating their partition progress metadata. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis, long consumerInactivityTimeoutMillis,
                         long maxRetainedMemoryBytes, long memoryRetentionMillis, long fileRetentionMillis,
                         ConsumerMetadataLimits consumerMetadataLimits) throws IOException {
        this.consumerMetadataLimits = Objects.requireNonNull(consumerMetadataLimits, "consumerMetadataLimits");
        this.storageMode = Objects.requireNonNull(storageMode, "storageMode");
        if (storageMode == StorageMode.FILE && dataDirectory == null)
            throw new IllegalArgumentException("dataDirectory is required for file storage");
        this.dataDirectory = dataDirectory == null ? null : dataDirectory.toAbsolutePath().normalize();
        if (maxRecordBytes < 0 || maxRecordBytes > 15 * 1024 * 1024)
            throw new IllegalArgumentException("maxRecordBytes must be between 0 and 15 MiB");
        if (queueBlockBytes <= 0) throw new IllegalArgumentException("queueBlockBytes must be positive");
        if (ackTimeoutMillis <= 0 || consumerInactivityTimeoutMillis <= 0)
            throw new IllegalArgumentException("consumer timeouts must be positive");
        this.maxRecordBytes = maxRecordBytes;
        this.queueBlockBytes = queueBlockBytes;
        this.ackTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(ackTimeoutMillis);
        this.consumerInactivityTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(consumerInactivityTimeoutMillis);
        this.memoryBudget = new MemoryLogBudget(maxRetainedMemoryBytes);
        if (memoryRetentionMillis < 0 || memoryRetentionMillis > TimeUnit.DAYS.toMillis(365))
            throw new IllegalArgumentException("memory retention must be 0..365 days");
        memoryRetentionNanos = TimeUnit.MILLISECONDS.toNanos(memoryRetentionMillis);
        if (fileRetentionMillis < 0 || fileRetentionMillis > TimeUnit.DAYS.toMillis(365))
            throw new IllegalArgumentException("file retention must be 0..365 days");
        if (storageMode == StorageMode.FILE) {
            Files.createDirectories(this.dataDirectory);
            try { recoverTopics(); }
            catch (IOException | RuntimeException | Error failure) {
                for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions) {
                    try { log.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }
        maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task ->
                Thread.ofPlatform().daemon().name("setu-consumer-maintenance").unstarted(task));
        maintenance.scheduleWithFixedDelay(() -> {
            try {
                expireConsumers(System.nanoTime());
                if (storageMode == StorageMode.IN_MEMORY && memoryRetentionNanos > 0 && !closed)
                    for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions)
                        ((io.setu.storage.log.InMemoryMessageLog) log).discardExpired(System.nanoTime(), memoryRetentionNanos);
            } catch (Exception e) { if (!closed) LOG.warn("Consumer maintenance failed", e); }
        }, 10, 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (storageMode == StorageMode.FILE && fileRetentionMillis > 0)
            maintenance.scheduleWithFixedDelay(() -> {
                for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions) {
                    if (closed) return;
                    try { log.discardExpiredFiles(fileRetentionMillis); }
                    catch (Exception error) { if (!closed) LOG.warn("File retention cleanup failed", error); }
                }
            }, 0, 60, TimeUnit.SECONDS);
        LOG.info("Broker opened storage={} data_directory={} recovered_topics={} max_record_bytes={} queue_block_bytes={} memory_log_budget_bytes={} ack_timeout_ms={} inactivity_timeout_ms={} file_retention_ms={}",
                storageMode, this.dataDirectory, topics.size(), maxRecordBytes, queueBlockBytes,
                maxRetainedMemoryBytes, ackTimeoutMillis, consumerInactivityTimeoutMillis, fileRetentionMillis);
    }

    /** Accounted memory-log payload/chunk bytes, including append reservations; excludes protocol buffers and file indexes. */
    public long retainedInMemoryBytes() { return memoryBudget.usedBytes(); }

    @Override
    public BrokerResponse execute(BrokerRequest request) throws IOException {
        ensureOpen();
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
                    file = partitionDir;
                }
                partitions.add(MessageLogs.open(storageMode, file, maxRecordBytes, queueBlockBytes, memoryBudget, memoryRetentionNanos > 0));
            }
            topics.put(request.topic(), new TopicState(List.copyOf(partitions)));
            LOG.info("Topic created topic={} partitions={} storage={}", request.topic(), partitions.size(), storageMode);
        } catch (IOException | RuntimeException failure) {
            closeLogs(partitions);
            throw failure;
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse produce(BrokerRequest request) throws IOException {
        var value = request.payload();
        if (value.length() > maxRecordBytes)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Record exceeds configured maximum size");
        MessageLog log = partition(request.topic(), request.partition());
        long offset;
        try { offset = log.appendValues(List.of(value)); }
        catch (LogCapacityExceededException exhausted) {
            throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, exhausted.getMessage(), exhausted);
        }
        if (storageMode == StorageMode.IN_MEMORY) reclaimAfterAppend(request.topic(), request.partition());
        signalData(request.topic(), request.partition());
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", offset, List.of(), Map.of());
    }

    private BrokerResponse produceBatch(BrokerRequest request) throws IOException {
        var values = request.payloads();
        if (values.isEmpty()) throw new BrokerException(ErrorCode.BAD_REQUEST, "Produce batch must not be empty");
        for (var value : values) {
            if (value.length() > maxRecordBytes)
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Record exceeds configured maximum size");
        }
        MessageLog log = partition(request.topic(), request.partition());
        long firstOffset;
        try { firstOffset = log.appendValues(values); }
        catch (LogCapacityExceededException exhausted) {
            throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, exhausted.getMessage(), exhausted);
        }
        if (storageMode == StorageMode.IN_MEMORY) reclaimAfterAppend(request.topic(), request.partition());
        signalData(request.topic(), request.partition());
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
        PartitionProgress progress = topics.get(request.topic()).progress.get(request.partition());
        if (request.waitMillis() > 0 && request.maxBytes() > 0 && startOffset >= log.nextOffset()) {
            progress.lock.lock();
            try {
                // Register before rechecking the published offset. Producers may skip
                // signalling only when there are no registered long polls.
                progress.waiters++;
                long remaining = TimeUnit.MILLISECONDS.toNanos(request.waitMillis());
                while (!closed && startOffset >= log.nextOffset() && remaining > 0) {
                    if (consumer != null) {
                        if (consumers.get(new ConsumerKey(request.groupId(), request.memberId())) != consumer) break;
                        consumer.touch(System.nanoTime());
                    }
                    try { remaining = progress.appended.awaitNanos(remaining); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Fetch interrupted", e); }
                }
            } finally { progress.waiters--; progress.lock.unlock(); }
        }
        ensureOpen();
        if (consumer != null && consumers.get(new ConsumerKey(request.groupId(), request.memberId())) != consumer)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Slow consumer was evicted; rejoin the group");
        if ((storageMode == StorageMode.FILE || memoryRetentionNanos > 0) && startOffset < log.firstOffset())
            throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE,
                    "Message retention expired; resume at offset " + log.firstOffset());
        // Memory fetches use immutable snapshots outside the coordination lock.
        // File indexes/I/O still serialize: keep their existing fetch scheduling
        // boundary so group writers cannot gain throughput by starving consumers.
        List<ProtocolRecord> records;
        if (storageMode == StorageMode.FILE) progress.lock.lock();
        try {
            records = log.read(startOffset, request.maxBytes(), io.setu.core.protocol.ResponseBuffers::reserve).stream()
                .map(record -> new ProtocolRecord(record.offset(), record.payload())).toList();
        } finally { if (storageMode == StorageMode.FILE) progress.lock.unlock(); }
        if (storageMode == StorageMode.FILE || memoryRetentionNanos > 0) {
            // Expiry may win between the boundary check and snapshot capture.
            // Preserve a snapshot that starts at the requested offset, but report
            // a gap if read had to clamp its start to a newer retained boundary.
            long retained = records.isEmpty() ? log.firstOffset() : records.getFirst().offset();
            if (startOffset < retained)
                throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE,
                        "Message retention expired; resume at offset " + retained);
        }
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

    private BrokerResponse commitOffset(BrokerRequest request) throws IOException {
        MessageLog log = partition(request.topic(), request.partition());
        if (request.offset() > log.nextOffset()) {
            throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE, "Committed offset is beyond the partition end");
        }
        if (request.memberId() != null) {
            ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
            if (consumer == null || !consumer.topic.equals(request.topic()))
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered for this topic");
            synchronized (consumer) {
                if (consumers.get(new ConsumerKey(request.groupId(), request.memberId())) != consumer)
                    throw new BrokerException(ErrorCode.BAD_REQUEST, "Slow consumer was evicted; rejoin the group");
                long previous = consumer.nextOffsets.getOrDefault(request.partition(), log.nextOffset());
                if (request.offset() < previous)
                    throw new BrokerException(ErrorCode.BAD_REQUEST, "Acknowledged offset cannot move backwards");
                long fetched = consumer.fetchedOffsets.getOrDefault(request.partition(), previous);
                if (request.offset() > fetched)
                    throw new BrokerException(ErrorCode.BAD_REQUEST, "Cannot acknowledge records that were not fetched");
                consumer.nextOffsets.put(request.partition(), request.offset());
                consumer.acknowledged(request.partition(), request.offset(), previous, System.nanoTime());
                updateProgress(request.topic(), request.partition(), new ConsumerKey(request.groupId(), request.memberId()), request.offset());
            }
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private synchronized BrokerResponse joinGroup(BrokerRequest request) {
        if (!topics.containsKey(request.topic())) {
            throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + request.topic());
        }
        validateConsumerId(request.groupId());
        validateConsumerId(request.memberId());
        ConsumerKey key = new ConsumerKey(request.groupId(), request.memberId());
        ConsumerState existing = consumers.get(key);
        if (existing != null && !existing.topic.equals(request.topic()))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Member is already registered for a different topic");
        if (existing == null) {
            int partitions = topics.get(request.topic()).partitions.size();
            if (consumers.size() >= consumerMetadataLimits.maxMembers()
                    || partitions > consumerMetadataLimits.maxPartitionMemberships() - consumerPartitionMemberships)
                throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, "Consumer metadata limit reached");
            Map<Integer, Long> offsets = new ConcurrentHashMap<>();
            Map<Integer, Long> fetchedOffsets = new ConcurrentHashMap<>();
            TopicState topic = topics.get(request.topic());
            for (int i = 0; i < topic.partitions.size(); i++) {
                long initialOffset = storageMode == StorageMode.IN_MEMORY ? topic.partitions.get(i).nextOffset() : 0L;
                offsets.put(i, initialOffset);
                fetchedOffsets.put(i, initialOffset);
            }
            consumers.put(key, new ConsumerState(request.topic(), offsets, fetchedOffsets));
            consumerPartitionMemberships += partitions;
            consumers.get(key).touch(System.nanoTime());
            for (int i = 0; i < topic.partitions.size(); i++) updateProgress(request.topic(), i, key, offsets.get(i));
            if (LOG.isDebugEnabled()) LOG.debug("Consumer joined group={} member={} topic={} initial_offsets={}",
                    request.groupId(), request.memberId(), request.topic(), offsets);
            reclaimTopic(request.topic());
        } else {
            ConsumerState consumer = consumers.get(key);
            if (consumer != null) consumer.touch(System.nanoTime());
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private synchronized BrokerResponse leaveGroup(BrokerRequest request) {
        ConsumerState removed = consumers.remove(new ConsumerKey(request.groupId(), request.memberId()));
        String topic = removed == null ? null : removed.topic;
        if (removed != null) {
            consumerPartitionMemberships -= removed.nextOffsets.size();
            synchronized (removed) { removeProgress(new ConsumerKey(request.groupId(), request.memberId()), removed); }
        }
        if (topic != null) reclaimTopic(topic);
        if (LOG.isDebugEnabled()) LOG.debug("Consumer left group={} member={} topic={}", request.groupId(), request.memberId(), topic);
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse heartbeat(BrokerRequest request) {
        ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (consumer == null)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered");
        consumer.touch(System.nanoTime());
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private void expireConsumers(long now) {
        for (Map.Entry<ConsumerKey, ConsumerState> entry : consumers.entrySet()) {
            ConsumerState consumer = entry.getValue();
            if (!consumer.expired(now, ackTimeoutNanos, consumerInactivityTimeoutNanos)) continue;
            // Membership changes serialize with joins; the normal maintenance scan does not hold the broker lock.
            synchronized (this) {
                if (closed) return;
                synchronized (consumer) {
                    if (!consumer.expired(now, ackTimeoutNanos, consumerInactivityTimeoutNanos)) continue;
                    if (!consumers.remove(entry.getKey(), consumer)) continue;
                    consumerPartitionMemberships -= consumer.nextOffsets.size();
                    removeProgress(entry.getKey(), consumer);
                    LOG.warn("Consumer expired group={} member={} topic={} inactive_ms={} pending_ack_partitions={} acknowledged_offsets={} fetched_offsets={}",
                            entry.getKey().groupId, entry.getKey().memberId, consumer.topic,
                            TimeUnit.NANOSECONDS.toMillis(now - consumer.lastActivityNanos), consumer.pendingAckSince.keySet(),
                            consumer.nextOffsets, consumer.fetchedOffsets);
                }
            }
        }
    }

    private void reclaimConsumed(String topic, int partition) throws IOException {
        if (storageMode != StorageMode.IN_MEMORY) return;
        MessageLog log = partition(topic, partition);
        PartitionProgress progress = topics.get(topic).progress.get(partition);
        progress.lock.lock();
        try { log.discardBefore(progress.offsets.isEmpty() ? log.nextOffset() :
                Math.max(log.firstOffset(), progress.offsets.firstKey())); }
        finally { progress.lock.unlock(); }
    }

    private void reclaimAfterAppend(String topic, int partition) throws IOException {
        PartitionProgress progress = topics.get(topic).progress.get(partition);
        // With members present, only acknowledgement/removal can advance the
        // reclamation boundary; those paths already reclaim under the metadata lock.
        if (!progress.hasMembers) reclaimConsumed(topic, partition);
    }

    private void updateProgress(String topic, int partition, ConsumerKey key, long offset) {
        PartitionProgress progress = topics.get(topic).progress.get(partition);
        progress.lock.lock();
        try {
            Long previous = progress.members.put(key, offset);
            if (previous != null) progress.removeOffset(previous);
            progress.offsets.merge(offset, 1, Integer::sum);
            progress.hasMembers = true;
            reclaimConsumed(topic, partition);
        } catch (IOException e) { throw new IllegalStateException(e); }
        finally { progress.lock.unlock(); }
    }

    private void removeProgress(ConsumerKey key, ConsumerState consumer) {
        TopicState topic = topics.get(consumer.topic);
        if (topic == null) return;
        for (int i = 0; i < topic.partitions.size(); i++) {
            PartitionProgress progress = topic.progress.get(i);
            progress.lock.lock();
            try {
                Long previous = progress.members.remove(key);
                if (previous != null) progress.removeOffset(previous);
                progress.hasMembers = !progress.members.isEmpty();
                reclaimConsumed(consumer.topic, i);
                progress.appended.signalAll();
            } catch (IOException e) { throw new IllegalStateException(e); }
            finally { progress.lock.unlock(); }
        }
    }

    private void signalData(String topic, int partition) {
        PartitionProgress progress = topics.get(topic).progress.get(partition);
        if (storageMode == StorageMode.IN_MEMORY && progress.waiters == 0) return;
        progress.lock.lock();
        try { progress.appended.signalAll(); }
        finally { progress.lock.unlock(); }
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
                                partitionPaths.get(i), maxRecordBytes, queueBlockBytes, memoryBudget));
                    }
                    topics.put(name, new TopicState(List.copyOf(logs)));
                    LOG.info("Topic recovered topic={} partitions={} directory={}", name, logs.size(), topicPath);
                } catch (IOException | RuntimeException failure) {
                    closeLogs(logs);
                    throw failure;
                }
            }
        }
    }

    private static void validateConsumerId(String id) {
        if (id == null || id.isBlank() || id.length() > 4096
                || id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer identity must be 1..4096 UTF-8 bytes");
    }

    public int activeConsumerCount() { return consumers.size(); }
    public synchronized int activeConsumerPartitionMemberships() { return consumerPartitionMemberships; }

    private static void validateTopicName(String topic) {
        if (topic == null || topic.equals(".") || topic.equals("..")
                || !topic.matches("[A-Za-z0-9._-]{1,249}"))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Invalid topic name");
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Broker is closed");
    }

    private static void closeLogs(List<MessageLog> logs) {
        for (MessageLog log : logs) try { log.close(); }
        catch (IOException failure) { LOG.warn("Partition cleanup failed after broker initialization failure", failure); }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        // Do not interrupt a retention sweep while it owns Chronicle mappings or file channels.
        maintenance.shutdown();
        IOException failure = null;
        for (TopicState state : topics.values()) {
            for (PartitionProgress progress : state.progress) {
                progress.lock.lock();
                try { progress.appended.signalAll(); }
                finally { progress.lock.unlock(); }
            }
            for (MessageLog log : state.partitions) {
                try { log.close(); }
                catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
        LOG.info("Broker closed storage={} topics={} retained_budget_bytes={}", storageMode, topics.size(), memoryBudget.usedBytes());
    }

    private record TopicState(List<MessageLog> partitions, List<PartitionProgress> progress) {
        private TopicState(List<MessageLog> partitions) {
            this(partitions, partitions.stream().map(ignored -> new PartitionProgress()).toList());
        }
    }
    private static final class PartitionProgress {
        volatile int waiters;
        volatile boolean hasMembers;
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final java.util.concurrent.locks.Condition appended = lock.newCondition();
        final java.util.TreeMap<Long, Integer> offsets = new java.util.TreeMap<>();
        final Map<ConsumerKey, Long> members = new java.util.HashMap<>();
        void removeOffset(long offset) {
            offsets.computeIfPresent(offset, (ignored, count) -> count == 1 ? null : count - 1);
        }
    }
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

        private synchronized void recordFetch(int partition, long nextFetchedOffset, long now) {
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
            for (long since : pendingAckSince.values())
                if (now - since >= ackTimeoutNanos) return true;
            return false;
        }
    }
}
