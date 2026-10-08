package io.setu.core.broker;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.core.protocol.ProtocolRecord;
import io.setu.storage.log.FileMessageLog;
import io.setu.storage.log.MessageLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Single-process broker implementation backed by one file log per topic partition. */
public final class DefaultBroker implements Broker {
    private static final int MAX_TOPICS = 10_000;
    private static final int MAX_PARTITIONS_PER_TOPIC = 1_024;
    private final Path dataDirectory;
    private final int maxRecordBytes;
    private final Map<String, TopicState> topics = new ConcurrentHashMap<>();
    private final Map<String, Long> committedOffsets = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> groupMembers = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public DefaultBroker(Path dataDirectory, int maxRecordBytes) throws IOException {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        if (maxRecordBytes < 0 || maxRecordBytes > 15 * 1024 * 1024)
            throw new IllegalArgumentException("maxRecordBytes must be between 0 and 15 MiB");
        this.maxRecordBytes = maxRecordBytes;
        Files.createDirectories(this.dataDirectory);
        recoverTopics();
    }

    @Override
    public BrokerResponse execute(BrokerRequest request) throws IOException {
        ensureOpen();
        return switch (request.command()) {
            case CREATE_TOPIC -> createTopic(request);
            case PRODUCE -> produce(request);
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
        Path topicPath = dataDirectory.resolve(request.topic());
        try {
            Files.createDirectories(topicPath);
            for (int i = 0; i < request.partitionCount(); i++) {
                Path partitionDir = topicPath.resolve("partition-" + i);
                Files.createDirectories(partitionDir);
                partitions.add(new FileMessageLog(partitionDir.resolve("000000000000.log"), maxRecordBytes));
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
        return new BrokerResponse(request.correlationId(), request.command(), ErrorCode.NONE, "", offset, List.of(), Map.of());
    }

    private BrokerResponse fetch(BrokerRequest request) throws IOException {
        MessageLog log = partition(request.topic(), request.partition());
        if (request.offset() > log.nextOffset()) {
            throw new BrokerException(ErrorCode.OFFSET_OUT_OF_RANGE, "Offset is beyond the partition end");
        }
        List<ProtocolRecord> records = log.read(request.offset(), request.maxBytes()).stream()
                .map(record -> new ProtocolRecord(record.offset(), record.value())).toList();
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
        committedOffsets.put(offsetKey(request.groupId(), request.topic(), request.partition()), request.offset());
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse joinGroup(BrokerRequest request) {
        if (!topics.containsKey(request.topic())) {
            throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + request.topic());
        }
        Map<String, String> members = groupMembers.computeIfAbsent(request.groupId(), ignored -> new ConcurrentHashMap<>());
        String existing = members.putIfAbsent(request.memberId(), request.topic());
        if (existing != null && !existing.equals(request.topic())) {
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Member is already registered for a different topic");
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse leaveGroup(BrokerRequest request) {
        Map<String, String> members = groupMembers.get(request.groupId());
        if (members != null) members.remove(request.memberId());
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private BrokerResponse heartbeat(BrokerRequest request) {
        Map<String, String> members = groupMembers.get(request.groupId());
        if (members == null || !members.containsKey(request.memberId())) {
            throw new BrokerException(ErrorCode.BAD_REQUEST, "Consumer group member is not registered");
        }
        return BrokerResponse.ok(request.correlationId(), request.command());
    }

    private MessageLog partition(String topic, int partition) {
        TopicState state = topics.get(topic);
        if (state == null) throw new BrokerException(ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + topic);
        if (partition < 0 || partition >= state.partitions.size())
            throw new BrokerException(ErrorCode.UNKNOWN_PARTITION, "Unknown partition " + partition + " for " + topic);
        return state.partitions.get(partition);
    }

    private void recoverTopics() throws IOException {
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
                        logs.add(new FileMessageLog(partitionPaths.get(i).resolve("000000000000.log"), maxRecordBytes));
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
}
