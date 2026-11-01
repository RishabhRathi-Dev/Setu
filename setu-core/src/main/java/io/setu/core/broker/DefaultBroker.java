package io.setu.core.broker;

import io.setu.core.logging.LogText;
import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.ProtocolRecord;
import io.setu.core.protocol.ProtocolCodec;
import io.setu.core.protocol.TraceContext;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ConsumerCommit;
import io.setu.core.protocol.GroupAssignment;
import io.setu.core.protocol.AcknowledgementMode;
import io.setu.storage.log.MessageLog;
import io.setu.storage.log.MessageLogs;
import io.setu.storage.log.FileWriteQueueOptions;
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
    private final FileWriteQueueOptions fileWriteQueue;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final ConsumerMetadataLimits consumerMetadataLimits;
    private int consumerPartitionMemberships;
    private final Map<ConsumerKey, ConsumerState> consumers = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private BrokerJournal consumerJournal;
    private volatile long journalCompactBytes = 64L * 1024 * 1024;
    private final long openedNanos = System.nanoTime();
    private long recoveryNanos;
    private final java.util.concurrent.atomic.LongAdder gateWaitNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.locks.ReentrantReadWriteLock transactionGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final ProducerLedger producers = new ProducerLedger();
    private final Map<GroupKey, ManagedGroup> managedGroups = new ConcurrentHashMap<>();
    private int managedPartitionOffsets;
    private volatile IOException failed;
    private static final int TRANSACTION_ENTRY = 0x53545831;
    // Package-scoped test seam only; never configured by clients or launcher properties.
    private TransactionFault transactionFault = stage -> { };
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

    /** Creates a broker with explicit per-partition FILE write admission. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis, long consumerInactivityTimeoutMillis,
                         long maxRetainedMemoryBytes, FileWriteQueueOptions fileWriteQueue) throws IOException {
        this(storageMode, dataDirectory, maxRecordBytes, queueBlockBytes, ackTimeoutMillis,
                consumerInactivityTimeoutMillis, maxRetainedMemoryBytes, 0, 0,
                ConsumerMetadataLimits.defaults(), fileWriteQueue);
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
        this(storageMode, dataDirectory, maxRecordBytes, queueBlockBytes, ackTimeoutMillis,
                consumerInactivityTimeoutMillis, maxRetainedMemoryBytes, memoryRetentionMillis,
                fileRetentionMillis, consumerMetadataLimits, FileWriteQueueOptions.defaults());
    }

    /** Configures FILE admission independently of memory retention and consumer metadata. */
    public DefaultBroker(StorageMode storageMode, Path dataDirectory, int maxRecordBytes,
                         long queueBlockBytes, long ackTimeoutMillis, long consumerInactivityTimeoutMillis,
                         long maxRetainedMemoryBytes, long memoryRetentionMillis, long fileRetentionMillis,
                         ConsumerMetadataLimits consumerMetadataLimits, FileWriteQueueOptions fileWriteQueue) throws IOException {
        this.fileWriteQueue = Objects.requireNonNull(fileWriteQueue, "fileWriteQueue");
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
            try {
                recoverTopics();
                Path journalPath = this.dataDirectory.resolve("@consumer-state");
                if (Files.exists(journalPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && !journalPath.toRealPath().startsWith(this.dataDirectory.toRealPath()))
                    throw new IOException("Consumer metadata directory escapes the data directory");
                consumerJournal = new BrokerJournal(journalPath, queueBlockBytes);
                recoverConsumers();
            }
            catch (IOException | RuntimeException | Error failure) {
                if (consumerJournal != null) {
                    try { consumerJournal.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions()) {
                    try { log.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }
        recoveryNanos = System.nanoTime() - openedNanos;
        maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task ->
                Thread.ofPlatform().daemon().name("setu-consumer-maintenance").unstarted(task));
        maintenance.scheduleWithFixedDelay(() -> {
            if (closed || failed != null) return;
            try {
                expireConsumers(System.nanoTime());
                if (storageMode == StorageMode.IN_MEMORY && memoryRetentionNanos > 0 && !closed)
                    for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions())
                        ((io.setu.storage.log.InMemoryMessageLog) log).discardExpired(System.nanoTime(), memoryRetentionNanos);
            } catch (Exception e) { if (!closed) LOG.warn("Consumer maintenance failed", e); }
        }, 10, 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (storageMode == StorageMode.FILE) maintenance.scheduleWithFixedDelay(() -> {
            if (closed || failed != null) return;
            try {
                if (journalCompactBytes > 0 && consumerJournal.appendedBytes() >= journalCompactBytes) compactJournal();
                consumerJournal.refreshDiskBytes();
            } catch (Exception error) { if (!closed) LOG.warn("Journal maintenance failed", error); }
        }, 1, 1, TimeUnit.SECONDS);
        if (storageMode == StorageMode.FILE && fileRetentionMillis > 0)
            maintenance.scheduleWithFixedDelay(() -> {
                for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions()) {
                    if (closed || failed != null) return;
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
        if (request.command() == Command.FETCH) return fetch(request);
        if (storageMode != StorageMode.FILE) return dispatch(request);
        var gate = request.command() == Command.TRANSACT || request.command() == Command.PRODUCE_IDEMPOTENT
                ? transactionGate.writeLock() : transactionGate.readLock();
        long waiting = System.nanoTime();
        gate.lock();
        gateWaitNanos.add(System.nanoTime() - waiting);
        try { return dispatch(request); }
        finally { gate.unlock(); }
    }

    private BrokerResponse dispatch(BrokerRequest request) throws IOException {
        ensureOpen();
        return switch (request.command()) {
            case CREATE_TOPIC -> createTopic(request);
            case PRODUCE -> produce(request);
            case PRODUCE_BATCH -> produceBatch(request);
            case PRODUCE_CONFIGURED -> produceConfigured(request);
            case PRODUCE_IDEMPOTENT, TRANSACT -> transactionalWrite(request);
            case FETCH -> fetch(request);
            case METADATA -> metadata(request);
            case COMMIT_OFFSET -> commitOffset(request);
            case FETCH_OFFSET -> fetchOffset(request);
            case JOIN_GROUP -> joinGroup(request);
            case JOIN_MANAGED_GROUP -> joinGroup(request);
            case GROUP_ASSIGNMENT -> groupAssignment(request);
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
        Path topicPath = storageMode == StorageMode.FILE ? topicDirectory(request.topic()) : null;
        try {
            if (storageMode == StorageMode.FILE) {
                Files.createDirectories(topicPath);
                if (!topicPath.toRealPath().startsWith(dataDirectory.toRealPath()))
                    throw new BrokerException(ErrorCode.BAD_REQUEST, "Topic directory escapes the data directory");
            }
            for (int i = 0; i < request.partitionCount(); i++) {
                Path file = null;
                if (storageMode == StorageMode.FILE) {
                    Path partitionDir = topicPath.resolve("partition-" + i);
                    if (Files.exists(partitionDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            && !partitionDir.toRealPath().startsWith(topicPath.toRealPath()))
                        throw new BrokerException(ErrorCode.BAD_REQUEST, "Partition directory escapes the topic directory");
                    Files.createDirectories(partitionDir);
                    file = partitionDir;
                }
                partitions.add(MessageLogs.open(storageMode, file, maxRecordBytes, queueBlockBytes, memoryBudget, memoryRetentionNanos > 0, fileWriteQueue));
            }
            topics.put(request.topic(), new TopicState(List.copyOf(partitions)));
            LOG.info("Topic created topic={} partitions={} storage={}", LogText.escape(request.topic()), partitions.size(), storageMode);
        } catch (IOException | RuntimeException failure) {
            closeLogs(partitions);
            throw failure;
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private Path topicDirectory(String topic) {
        Path candidate = dataDirectory.resolve(topic).normalize();
        if (!candidate.startsWith(dataDirectory))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Topic path must remain inside the data directory");
        if (!dataDirectory.equals(candidate.getParent()))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Topic path must be a direct child of the data directory");
        return candidate;
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

    private BrokerResponse produceConfigured(BrokerRequest request) throws IOException {
        if (request.options().acknowledgementMode() == AcknowledgementMode.DURABLE) return produceBatch(request);
        if (request.payloads().isEmpty()) throw new BrokerException(ErrorCode.BAD_REQUEST, "Empty batch");
        for (var value : request.payloads()) if (value.length() > maxRecordBytes)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Record exceeds configured maximum size");
        MessageLog log = partition(request.topic(), request.partition());
        try {
            log.appendValuesAsync(request.payloads()).whenComplete((offset, failure) -> {
                if (failure != null) LOG.error("Accepted produce later failed topic={} partition={}",
                        LogText.escape(request.topic()), request.partition(), failure);
                else {
                    try {
                        if (storageMode == StorageMode.IN_MEMORY) reclaimAfterAppend(request.topic(), request.partition());
                        signalData(request.topic(), request.partition());
                    } catch (IOException error) { LOG.warn("Accepted produce cleanup failed", error); }
                }
            });
        } catch (LogCapacityExceededException exhausted) {
            throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, exhausted.getMessage(), exhausted);
        }
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", -1, List.of(), Map.of());
    }

    private synchronized BrokerResponse transactionalWrite(BrokerRequest request) throws IOException {
        if (storageMode != StorageMode.FILE)
            throw new BrokerException(ErrorCode.UNSUPPORTED_OPERATION, "Transactional and idempotent writes require FILE storage");
        byte[] canonical = canonicalRequest(request);
        byte[] fingerprint = ProducerLedger.fingerprint(canonical);
        Long duplicate = producers.duplicate(request, fingerprint);
        if (duplicate != null) return produced(request, duplicate);
        MessageLog output = partition(request.topic(), request.partition());
        for (var value : request.payloads()) if (value.length() > maxRecordBytes)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Record exceeds configured maximum size");
        if (request.command() == Command.TRANSACT) validateTransactionCommit(request.options().commit());
        output.awaitPendingWrites();
        long firstOffset = output.nextOffset();
        Math.addExact(firstOffset, request.valueCount());
        byte[] entry = java.nio.ByteBuffer.allocate(12 + canonical.length)
                .putInt(TRANSACTION_ENTRY).putLong(firstOffset).put(canonical).array();
        transactionFault.at(TransactionStage.BEFORE_JOURNAL);
        try { consumerJournal.append(entry); }
        catch (LogCapacityExceededException exhausted) {
            throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, exhausted.getMessage(), exhausted);
        } catch (IOException failure) {
            failed = new IOException("Transaction journal failed; restart the broker", failure);
            throw failed;
        }
        try {
            transactionFault.at(TransactionStage.JOURNAL_FORCED);
            applyTransaction(request, firstOffset, fingerprint);
            transactionFault.at(TransactionStage.BEFORE_REPLY);
        } catch (IOException | RuntimeException failure) {
            failed = new IOException("Committed transaction requires recovery; restart the broker", failure);
            throw failed;
        } catch (Error failure) {
            failed = new IOException("Committed transaction requires recovery; restart the broker", failure);
            throw failure;
        }
        return produced(request, firstOffset);
    }

    private static byte[] canonicalRequest(BrokerRequest request) throws IOException {
        // Trace context describes a wire attempt, never the producer operation or durable journal contents.
        return ProtocolCodec.encodeRequest(new BrokerRequest(0, request.command(), request.topic(), request.partition(),
                request.offset(), request.maxBytes(), request.payload(), request.partitionCount(), request.groupId(),
                request.memberId(), request.payloads(), request.waitMillis(), request.options()));
    }

    private static BrokerResponse produced(BrokerRequest request, long offset) {
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", offset, List.of(), Map.of());
    }

    private void validateTransactionCommit(ConsumerCommit commit) {
        BrokerRequest request = commitRequest(commit);
        validateOwnership(request);
        MessageLog input = partition(commit.topic(), commit.partition());
        ConsumerState consumer = consumers.get(new ConsumerKey(commit.groupId(), commit.memberId()));
        if (consumer == null || !consumer.topic.equals(commit.topic()))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Transaction input member is not registered");
        synchronized (consumer) {
            long previous = consumer.nextOffsets.get(commit.partition());
            if (commit.nextOffset() <= previous || commit.nextOffset() > input.nextOffset()
                    || commit.nextOffset() > consumer.fetchedOffsets.get(commit.partition()))
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Transaction input must be fetched and advance monotonically");
        }
    }

    private static BrokerRequest commitRequest(ConsumerCommit commit) {
        return new BrokerRequest(0, Command.COMMIT_OFFSET, commit.topic(), commit.partition(), commit.nextOffset(),
                0, null, 0, commit.groupId(), commit.memberId()).withGeneration(commit.generation());
    }

    private void applyTransaction(BrokerRequest request, long firstOffset, byte[] fingerprint) throws IOException {
        if (request.command() == Command.TRANSACT) {
            ConsumerCommit commit = request.options().commit();
            ConsumerState consumer = consumers.get(new ConsumerKey(commit.groupId(), commit.memberId()));
            if (consumer == null || !consumer.topic.equals(commit.topic())
                    || commit.nextOffset() <= consumer.nextOffsets.getOrDefault(commit.partition(), Long.MAX_VALUE)
                    || commit.nextOffset() > partition(commit.topic(), commit.partition()).nextOffset())
                throw new IOException("Invalid transactional input progress");
            validateOwnership(commitRequest(commit));
        }
        MessageLog output = partition(request.topic(), request.partition());
        if (firstOffset < 0 || output.nextOffset() < firstOffset) throw new IOException("Transaction output has an offset gap");
        long end = Math.addExact(firstOffset, request.valueCount());
        long verified = Math.max(firstOffset, output.firstOffset());
        while (verified < Math.min(end, output.nextOffset())) {
            var records = output.read(verified, 15 * 1024 * 1024);
            if (records.isEmpty()) throw new IOException("Missing transaction output");
            for (var record : records) {
                if (record.offset() >= end) break;
                int index = Math.toIntExact(record.offset() - firstOffset);
                if (record.offset() != verified || !java.util.Arrays.equals(record.value(), request.payloads().get(index).copy()))
                    throw new IOException("Transaction output differs from journal");
                verified++;
            }
        }
        if (output.nextOffset() < end) {
            int written = Math.toIntExact(output.nextOffset() - firstOffset);
            output.appendValues(request.payloads().subList(written, request.valueCount()));
        }
        transactionFault.at(TransactionStage.OUTPUT_FORCED);
        if (request.command() == Command.TRANSACT) applyRecoveredCommit(commitRequest(request.options().commit()));
        transactionFault.at(TransactionStage.INPUT_APPLIED);
        producers.record(request, fingerprint, firstOffset);
        signalData(request.topic(), request.partition());
    }

    private synchronized BrokerResponse groupAssignment(BrokerRequest request) {
        ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        ManagedGroup group = managedGroups.get(new GroupKey(request.groupId(), request.topic()));
        if (consumer == null || !consumer.managed || !consumer.topic.equals(request.topic()) || group == null)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Managed member is not registered for this topic");
        Map<Integer, Long> assigned = new java.util.HashMap<>();
        for (int partition = 0; partition < group.offsets.size(); partition++)
            if (group.owner(partition).equals(request.memberId())) assigned.put(partition, group.offsets.get(partition));
        consumer.touch(System.nanoTime());
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", -1,
                List.of(), Map.of(), new GroupAssignment(group.generation, assigned));
    }

    private void validateOwnership(BrokerRequest request) {
        if (request.memberId() == null) return;
        ConsumerState member = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (member == null || !member.managed) return;
        ManagedGroup group = managedGroups.get(new GroupKey(request.groupId(), member.topic));
        if (group == null || group.generation != request.options().generation()
                || !group.owner(request.partition()).equals(request.memberId()))
            throw new BrokerException(ErrorCode.STALE_GENERATION, "Assignment changed; query GROUP_ASSIGNMENT before fetching or committing");
    }

    private void rebalance(GroupKey key) {
        ManagedGroup group = managedGroups.get(key);
        group.members = consumers.entrySet().stream().filter(entry -> entry.getValue().managed
                        && entry.getKey().groupId().equals(key.groupId()) && entry.getValue().topic.equals(key.topic()))
                .map(entry -> entry.getKey().memberId()).sorted().toList();
        group.generation = Math.addExact(group.generation, 1);
        for (String member : group.members) {
            ConsumerState consumer = consumers.get(new ConsumerKey(key.groupId(), member));
            synchronized (consumer) {
                consumer.nextOffsets.putAll(group.offsets);
                consumer.fetchedOffsets.putAll(group.offsets);
                consumer.pendingAckSince.clear();
            }
        }
        for (int partition = 0; partition < group.offsets.size(); partition++) signalData(key.topic(), partition);
    }

    private BrokerResponse fetch(BrokerRequest request) throws IOException {
        ensureOpen();
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
            if (consumer.managed) validateOwnership(request);
        }
        PartitionProgress progress = topics.get(request.topic()).progress().get(request.partition());
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
                        if (consumer.managed) validateOwnership(request);
                        consumer.touch(System.nanoTime());
                    }
                    try { remaining = progress.appended.awaitNanos(remaining); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Fetch interrupted", e); }
                }
            } finally { progress.waiters--; progress.lock.unlock(); }
        }
        if (storageMode != StorageMode.FILE) return fetchSnapshot(request, log, startOffset, consumer, progress);
        var gate = transactionGate.readLock();
        gate.lock();
        try {
            if (consumer != null && consumer.managed) {
                synchronized (this) { return fetchSnapshot(request, log, startOffset, consumer, progress); }
            }
            return fetchSnapshot(request, log, startOffset, consumer, progress);
        } finally { gate.unlock(); }
    }

    private BrokerResponse fetchSnapshot(BrokerRequest request, MessageLog log, long startOffset,
                                          ConsumerState consumer, PartitionProgress progress) throws IOException {
        ensureOpen();
        validateOwnership(request);
        if (consumer != null && consumer.managed)
            startOffset = Math.max(startOffset, consumer.nextOffsets.get(request.partition()));
        if (consumer != null && consumers.get(new ConsumerKey(request.groupId(), request.memberId())) != consumer)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Slow consumer was evicted; rejoin the group");
        if ((storageMode == StorageMode.FILE || memoryRetentionNanos > 0) && startOffset < log.firstOffset())
            throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE,
                    "Message retention expired; resume at offset " + log.firstOffset());
        // Memory fetches use immutable snapshots outside the coordination lock.
        // File indexes/I/O still serialize: keep their existing fetch scheduling
        // boundary so group writers cannot gain throughput by starving consumers.
        List<ProtocolRecord> records;
        if (storageMode == StorageMode.FILE) {
            progress.lock.lock();
            try { records = readRecords(log, startOffset, request.maxBytes()); }
            finally { progress.lock.unlock(); }
        } else {
            records = readRecords(log, startOffset, request.maxBytes());
        }
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

    private static List<ProtocolRecord> readRecords(MessageLog log, long offset, int maxBytes) throws IOException {
        return log.read(offset, maxBytes, io.setu.core.protocol.ResponseBuffers::reserve).stream()
                .map(record -> new ProtocolRecord(record.offset(), record.payload())).toList();
    }

    private BrokerResponse metadata(BrokerRequest request) {
        Map<String, Integer> result = topics.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().partitions().size()));
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", -1, List.of(), result);
    }

    private BrokerResponse commitOffset(BrokerRequest request) throws IOException {
        ConsumerState member = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (member != null && member.managed) {
            synchronized (this) { return commitOffsetChecked(request); }
        }
        return commitOffsetChecked(request);
    }

    private BrokerResponse commitOffsetChecked(BrokerRequest request) throws IOException {
        validateOwnership(request);
        MessageLog log = partition(request.topic(), request.partition());
        if (request.offset() < 0)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Committed offset must be non-negative");
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
                if (request.offset() != previous) persistConsumer(request);
                consumer.nextOffsets.put(request.partition(), request.offset());
                if (consumer.managed) managedGroups.get(new GroupKey(request.groupId(), consumer.topic))
                        .offsets.put(request.partition(), request.offset());
                consumer.acknowledged(request.partition(), request.offset(), previous, System.nanoTime());
                updateProgress(request.topic(), request.partition(), new ConsumerKey(request.groupId(), request.memberId()), request.offset());
            }
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse fetchOffset(BrokerRequest request) {
        validateOwnership(request);
        partition(request.topic(), request.partition());
        ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (consumer == null || !consumer.topic.equals(request.topic()))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered for this topic");
        synchronized (consumer) {
            if (consumers.get(new ConsumerKey(request.groupId(), request.memberId())) != consumer)
                throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is no longer registered");
            consumer.touch(System.nanoTime());
            return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "",
                    consumer.nextOffsets.get(request.partition()), List.of(), Map.of());
        }
    }

    private synchronized BrokerResponse joinGroup(BrokerRequest request) throws IOException {
        ensureOpen();
        if (!topics.containsKey(request.topic())) {
            throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + request.topic());
        }
        validateConsumerId(request.groupId());
        validateConsumerId(request.memberId());
        ConsumerKey key = new ConsumerKey(request.groupId(), request.memberId());
        boolean managed = request.command() == Command.JOIN_MANAGED_GROUP;
        GroupKey groupKey = new GroupKey(request.groupId(), request.topic());
        if (managed && storageMode != StorageMode.FILE)
            throw new BrokerException(ErrorCode.UNSUPPORTED_OPERATION, "Managed groups require FILE storage");
        if (managedGroups.containsKey(groupKey) != managed
                && (managedGroups.containsKey(groupKey) || consumers.entrySet().stream().anyMatch(entry ->
                entry.getKey().groupId().equals(request.groupId()) && entry.getValue().topic.equals(request.topic()))))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Cannot mix explicit and managed membership");
        if (managed && !managedGroups.containsKey(groupKey)
                && (managedGroups.size() >= consumerMetadataLimits.maxMembers()
                || topics.get(request.topic()).partitions().size()
                > consumerMetadataLimits.maxPartitionMemberships() - managedPartitionOffsets))
            throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, "Durable group offset limit reached");
        ConsumerState existing = consumers.get(key);
        if (existing != null && !existing.topic.equals(request.topic()))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Member is already registered for a different topic");
        if (existing == null) {
            int partitions = topics.get(request.topic()).partitions().size();
            if (consumers.size() >= consumerMetadataLimits.maxMembers()
                    || partitions > consumerMetadataLimits.maxPartitionMemberships() - consumerPartitionMemberships)
                throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, "Consumer metadata limit reached");
            Map<Integer, Long> offsets = new ConcurrentHashMap<>();
            Map<Integer, Long> fetchedOffsets = new ConcurrentHashMap<>();
            TopicState topic = topics.get(request.topic());
            for (int i = 0; i < topic.partitions().size(); i++) {
                long initialOffset = storageMode == StorageMode.IN_MEMORY ? topic.partitions().get(i).nextOffset() : 0L;
                offsets.put(i, initialOffset);
                fetchedOffsets.put(i, initialOffset);
            }
            persistConsumer(request);
            consumers.put(key, new ConsumerState(request.topic(), offsets, fetchedOffsets, managed));
            consumerPartitionMemberships += partitions;
            consumers.get(key).touch(System.nanoTime());
            for (int i = 0; i < topic.partitions().size(); i++) updateProgress(request.topic(), i, key, offsets.get(i));
            if (managed) {
                if (!managedGroups.containsKey(groupKey)) {
                    managedGroups.put(groupKey, new ManagedGroup(topic.partitions().size()));
                    managedPartitionOffsets += topic.partitions().size();
                }
                rebalance(groupKey);
            }
            if (LOG.isDebugEnabled()) LOG.debug("Consumer joined group={} member={} topic={} initial_offsets={}",
                    LogText.escape(request.groupId()), LogText.escape(request.memberId()), LogText.escape(request.topic()), offsets);
            reclaimTopic(request.topic());
        } else {
            ConsumerState consumer = consumers.get(key);
            if (consumer != null) consumer.touch(System.nanoTime());
        }
        return managed ? groupAssignment(request) : BrokerResponse.ok(request.correlationId(), request.command());
    }

    private synchronized BrokerResponse leaveGroup(BrokerRequest request) throws IOException {
        ensureOpen();
        ConsumerKey key = new ConsumerKey(request.groupId(), request.memberId());
        ConsumerState removed = consumers.get(key);
        String topic = removed == null ? null : removed.topic;
        if (removed != null) {
            synchronized (removed) {
                persistConsumer(request);
                consumers.remove(key);
                consumerPartitionMemberships -= removed.nextOffsets.size();
                removeProgress(key, removed);
                if (removed.managed) rebalance(new GroupKey(request.groupId(), removed.topic));
            }
        }
        if (topic != null) reclaimTopic(topic);
        if (LOG.isDebugEnabled()) LOG.debug("Consumer left group={} member={} topic={}", LogText.escape(request.groupId()), LogText.escape(request.memberId()), LogText.escape(topic));
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse heartbeat(BrokerRequest request) {
        ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (consumer == null)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered");
        consumer.touch(System.nanoTime());
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private void expireConsumers(long now) throws IOException {
        for (Map.Entry<ConsumerKey, ConsumerState> entry : consumers.entrySet()) {
            ConsumerState consumer = entry.getValue();
            if (!consumer.expired(now, ackTimeoutNanos, consumerInactivityTimeoutNanos)) continue;
            // Membership changes serialize with joins; the normal maintenance scan does not hold the broker lock.
            synchronized (this) {
                if (closed) return;
                synchronized (consumer) {
                    if (!consumer.expired(now, ackTimeoutNanos, consumerInactivityTimeoutNanos)) continue;
                    if (consumers.get(entry.getKey()) != consumer) continue;
                    persistConsumer(new BrokerRequest(0, Command.LEAVE_GROUP, null, -1, -1, -1,
                            null, 0, entry.getKey().groupId(), entry.getKey().memberId()));
                    if (!consumers.remove(entry.getKey(), consumer)) continue;
                    consumerPartitionMemberships -= consumer.nextOffsets.size();
                    removeProgress(entry.getKey(), consumer);
                    if (consumer.managed) rebalance(new GroupKey(entry.getKey().groupId(), consumer.topic));
                    LOG.warn("Consumer expired group={} member={} topic={} inactive_ms={} pending_ack_partitions={} acknowledged_offsets={} fetched_offsets={}",
                            LogText.escape(entry.getKey().groupId()), LogText.escape(entry.getKey().memberId()), LogText.escape(consumer.topic),
                            TimeUnit.NANOSECONDS.toMillis(now - consumer.lastActivityNanos), consumer.pendingAckSince.keySet(),
                            consumer.nextOffsets, consumer.fetchedOffsets);
                }
            }
        }
    }

    private void reclaimConsumed(String topic, int partition) throws IOException {
        if (storageMode != StorageMode.IN_MEMORY) return;
        MessageLog log = partition(topic, partition);
        PartitionProgress progress = topics.get(topic).progress().get(partition);
        progress.lock.lock();
        try { log.discardBefore(progress.offsets.isEmpty() ? log.nextOffset() :
                Math.max(log.firstOffset(), progress.offsets.firstKey())); }
        finally { progress.lock.unlock(); }
    }

    private void reclaimAfterAppend(String topic, int partition) throws IOException {
        PartitionProgress progress = topics.get(topic).progress().get(partition);
        // With members present, only acknowledgement/removal can advance the
        // reclamation boundary; those paths already reclaim under the metadata lock.
        if (!progress.hasMembers) reclaimConsumed(topic, partition);
    }

    private void updateProgress(String topic, int partition, ConsumerKey key, long offset) {
        PartitionProgress progress = topics.get(topic).progress().get(partition);
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
        for (int i = 0; i < topic.partitions().size(); i++) {
            PartitionProgress progress = topic.progress().get(i);
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
        PartitionProgress progress = topics.get(topic).progress().get(partition);
        if (storageMode == StorageMode.IN_MEMORY && progress.waiters == 0) return;
        progress.lock.lock();
        try { progress.appended.signalAll(); }
        finally { progress.lock.unlock(); }
    }

    private synchronized void reclaimTopic(String topic) {
        TopicState state = topics.get(topic);
        if (state == null || storageMode != StorageMode.IN_MEMORY) return;
        for (int partition = 0; partition < state.partitions().size(); partition++) {
            try { reclaimConsumed(topic, partition); }
            catch (IOException e) { throw new IllegalStateException("Unable to reclaim in-memory records", e); }
        }
    }

    private MessageLog partition(String topic, int partition) {
        TopicState state = topics.get(topic);
        if (state == null) throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + topic);
        if (partition < 0 || partition >= state.partitions().size())
            throw new BrokerException(ErrorCode.UNKNOWN_PARTITION, "Unknown partition " + partition + " for " + topic);
        return state.partitions().get(partition);
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
                                partitionPaths.get(i), maxRecordBytes, queueBlockBytes, memoryBudget, false, fileWriteQueue));
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

    private void persistConsumer(BrokerRequest request) throws IOException {
        if (consumerJournal != null) {
            byte[] encoded = ProtocolCodec.encodeRequest(request.withTraceContext(TraceContext.NONE));
            try { consumerJournal.append(encoded); }
            catch (LogCapacityExceededException exhausted) {
                throw new BrokerException(ErrorCode.QUOTA_EXCEEDED, exhausted.getMessage(), exhausted);
            }
            catch (IOException failure) { failed = failure; throw failure; }
        }
    }

    private void recoverConsumers() throws IOException {
        BrokerJournal journal = consumerJournal;
        consumerJournal = null;
        try {
            journal.restore(this::restoreCheckpoint);
            long offset = 0;
            while (offset < journal.nextOffset()) {
                var records = journal.read(offset, 15 * 1024 * 1024);
                if (records.isEmpty()) throw new IOException("Consumer metadata journal has a gap");
                for (var record : records) {
                    if (journal.hasCheckpoint() && record.offset() == 0 && java.util.Arrays.equals(record.value(), new byte[]{0})) {
                        offset = 1;
                        continue;
                    }
                    journal.recovered(record.value());
                    var encoded = java.nio.ByteBuffer.wrap(record.value());
                    if (encoded.remaining() >= 12 && encoded.getInt(0) == TRANSACTION_ENTRY) {
                        encoded.getInt();
                        long firstOffset = encoded.getLong();
                        byte[] body = new byte[encoded.remaining()];
                        encoded.get(body);
                        BrokerRequest transaction = ProtocolCodec.decodeRequest(body);
                        if (transaction.command() != Command.TRANSACT && transaction.command() != Command.PRODUCE_IDEMPOTENT)
                            throw new IOException("Invalid transactional journal command");
                        byte[] fingerprint = ProducerLedger.fingerprint(canonicalRequest(transaction));
                        if (producers.duplicate(transaction, fingerprint) != null)
                            throw new IOException("Duplicate transactional journal entry");
                        applyTransaction(transaction, firstOffset, fingerprint);
                        offset = record.offset() + 1;
                        continue;
                    }
                    BrokerRequest request = ProtocolCodec.decodeRequest(record.value());
                    switch (request.command()) {
                        case JOIN_GROUP, JOIN_MANAGED_GROUP -> joinGroup(request);
                        case LEAVE_GROUP -> leaveGroup(request);
                        case COMMIT_OFFSET -> applyRecoveredCommit(request);
                        default -> throw new IOException("Invalid consumer metadata command");
                    }
                    offset = record.offset() + 1;
                }
            }
            long now = System.nanoTime();
            consumers.values().forEach(consumer -> consumer.touch(now));
        } catch (BrokerException invalid) {
            throw new IOException("Invalid consumer metadata journal", invalid);
        } finally {
            consumerJournal = journal;
        }
    }

    private void applyRecoveredCommit(BrokerRequest request) throws IOException {
        ConsumerState consumer = consumers.get(new ConsumerKey(request.groupId(), request.memberId()));
        if (consumer == null || !consumer.topic.equals(request.topic())
                || request.offset() < consumer.nextOffsets.getOrDefault(request.partition(), Long.MAX_VALUE)
                || request.offset() > partition(request.topic(), request.partition()).nextOffset())
            throw new IOException("Invalid committed consumer progress");
        validateOwnership(request);
        long previous = consumer.nextOffsets.put(request.partition(), request.offset());
        consumer.fetchedOffsets.merge(request.partition(), request.offset(), Math::max);
        if (consumer.managed) managedGroups.get(new GroupKey(request.groupId(), consumer.topic))
                .offsets.put(request.partition(), request.offset());
        consumer.acknowledged(request.partition(), request.offset(), previous, System.nanoTime());
        updateProgress(request.topic(), request.partition(), new ConsumerKey(request.groupId(), request.memberId()), request.offset());
    }

    private static void validateConsumerId(String id) {
        if (id == null || id.isBlank() || id.length() > 4096
                || id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer identity must be 1..4096 UTF-8 bytes");
    }

    public int activeConsumerCount() { return consumers.size(); }

    /** Zero disables automatic compaction; explicit compactJournal remains available. */
    public void configureJournalCompaction(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Journal compaction bytes must be nonnegative");
        journalCompactBytes = bytes;
    }

    /** Checkpoints all durable metadata while excluding requests and membership maintenance. */
    public void compactJournal() throws IOException {
        transactionGate.writeLock().lock();
        try {
            synchronized (this) {
                ensureOpen();
                if (consumerJournal == null) throw new IOException("Journal compaction requires FILE storage");
                try {
                    consumerJournal.compact(output -> BrokerCheckpoint.write(output, managedGroups, consumers, producers));
                } catch (IOException | RuntimeException failure) {
                    failed = new IOException("Journal compaction requires recovery; restart the broker", failure);
                    throw failed;
                } catch (Error failure) {
                    failed = new IOException("Journal compaction requires recovery; restart the broker", failure);
                    throw failure;
                }
            }
        } finally { transactionGate.writeLock().unlock(); }
    }

    private void restoreCheckpoint(java.io.DataInputStream input) throws IOException {
        BrokerCheckpoint.read(input, topics, managedGroups, consumers, producers, consumerMetadataLimits);
        consumerPartitionMemberships = consumers.values().stream().mapToInt(member -> member.nextOffsets.size()).sum();
        managedPartitionOffsets = managedGroups.values().stream().mapToInt(group -> group.offsets.size()).sum();
        consumers.forEach((key, member) -> member.nextOffsets.forEach((partition, offset) ->
                updateProgress(member.topic, partition, key, offset)));
    }

    // Package-scoped deterministic failure seam; never exposed by the wire protocol or launcher.
    void journalFault(JournalFault fault) { consumerJournal.fault(fault); }
    synchronized void transactionFault(TransactionFault fault) { transactionFault = Objects.requireNonNull(fault); }

    public boolean isReady() { return !closed && failed == null; }
    public long recoveryNanos() { return recoveryNanos; }
    public long journalBytes() { return consumerJournal == null ? 0 : consumerJournal.diskBytes(); }
    public long journalCompactions() { return consumerJournal == null ? 0 : consumerJournal.compactions(); }
    public long gateWaitNanos() { return gateWaitNanos.sum(); }

    /** Bounded aggregate gauges, with no user-controlled metric labels. Samples are not transactional snapshots. */
    public Map<String, Long> operationalGauges() {
        long bytes = 0, requests = 0, flushNanos = 0, flushes = 0, lag = 0;
        for (TopicState topic : topics.values()) for (MessageLog log : topic.partitions()) {
            if (log instanceof io.setu.storage.log.GroupCommitMessageLog group) {
                bytes += group.outstandingBytes(); requests += group.outstandingRequests();
                flushNanos += group.flushNanos(); flushes += group.flushCount();
            }
        }
        for (var entry : consumers.entrySet()) {
            ConsumerState member = entry.getValue();
            TopicState topic = topics.get(member.topic);
            ManagedGroup group = managedGroups.get(new GroupKey(entry.getKey().groupId(), member.topic));
            for (var offset : member.nextOffsets.entrySet()) {
                if (member.managed && (group == null || !group.owner(offset.getKey()).equals(entry.getKey().memberId()))) continue;
                lag = Math.addExact(lag, Math.max(0, topic.partitions().get(offset.getKey()).nextOffset() - offset.getValue()));
            }
        }
        return Map.of("outstanding_write_bytes", bytes, "outstanding_write_requests", requests,
                "partition_flush_nanoseconds_total", flushNanos, "partition_flushes_total", flushes,
                "member_lag_records", lag, "active_consumers", (long) consumers.size(),
                "retained_memory_bytes", retainedInMemoryBytes(), "journal_bytes", journalBytes(),
                "journal_compactions_total", journalCompactions());
    }
    public synchronized int activeConsumerPartitionMemberships() { return consumerPartitionMemberships; }

    private static void validateTopicName(String topic) {
        if (topic == null || topic.equals(".") || topic.equals("..")
                || !topic.matches("[A-Za-z0-9._-]{1,249}"))
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Invalid topic name");
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Broker is closed");
        if (failed != null) throw new IOException("Broker journal failed; restart required", failed);
    }

    private static void closeLogs(List<MessageLog> logs) {
        for (MessageLog log : logs) try { log.close(); }
        catch (IOException failure) { LOG.warn("Partition cleanup failed after broker initialization failure", failure); }
    }

    @Override
    public void close() throws IOException {
        transactionGate.writeLock().lock();
        try { closeResources(); }
        finally { transactionGate.writeLock().unlock(); }
    }

    private synchronized void closeResources() throws IOException {
        if (closed) return;
        closed = true;
        // Do not interrupt a retention sweep while it owns Chronicle mappings or file channels.
        maintenance.shutdown();
        IOException failure = null;
        if (consumerJournal != null) {
            try { consumerJournal.close(); } catch (IOException error) { failure = error; }
        }
        for (TopicState state : topics.values()) {
            for (PartitionProgress progress : state.progress()) {
                progress.lock.lock();
                try { progress.appended.signalAll(); }
                finally { progress.lock.unlock(); }
            }
            for (MessageLog log : state.partitions()) {
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
}
