package io.setu.core.broker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Version-one checkpoint codec. Fetched positions and liveness clocks are deliberately not persisted. */
final class BrokerCheckpoint {
    private BrokerCheckpoint() { }

    static void write(DataOutputStream output, Map<GroupKey, ManagedGroup> groups,
                      Map<ConsumerKey, ConsumerState> members, ProducerLedger producers) throws IOException {
        output.writeInt(groups.size());
        for (var entry : groups.entrySet()) {
            output.writeUTF(entry.getKey().groupId()); output.writeUTF(entry.getKey().topic());
            output.writeLong(entry.getValue().generation);
            offsets(output, entry.getValue().offsets);
        }
        output.writeInt(members.size());
        for (var entry : members.entrySet()) {
            output.writeUTF(entry.getKey().groupId()); output.writeUTF(entry.getKey().memberId());
            output.writeUTF(entry.getValue().topic); output.writeBoolean(entry.getValue().managed);
            offsets(output, entry.getValue().nextOffsets);
        }
        producers.write(output);
    }

    static void read(DataInputStream input, Map<String, TopicState> topics, Map<GroupKey, ManagedGroup> groups,
                     Map<ConsumerKey, ConsumerState> members, ProducerLedger producers,
                     ConsumerMetadataLimits limits) throws IOException {
        int groupCount = count(input, limits.maxMembers()), groupPartitions = 0;
        for (int i = 0; i < groupCount; i++) {
            var key = new GroupKey(identity(input), input.readUTF());
            long generation = input.readLong();
            var offsets = offsets(input, key.topic(), topics);
            groupPartitions = Math.addExact(groupPartitions, offsets.size());
            if (generation < 1 || groupPartitions > limits.maxPartitionMemberships()) throw invalid();
            var group = new ManagedGroup(offsets.size());
            group.offsets.putAll(offsets); group.generation = generation;
            if (groups.putIfAbsent(key, group) != null) throw invalid();
        }
        int memberCount = count(input, limits.maxMembers()), memberPartitions = 0;
        for (int i = 0; i < memberCount; i++) {
            var key = new ConsumerKey(identity(input), identity(input));
            String topic = input.readUTF();
            boolean managed = input.readBoolean();
            var offsets = offsets(input, topic, topics);
            memberPartitions = Math.addExact(memberPartitions, offsets.size());
            var group = groups.get(new GroupKey(key.groupId(), topic));
            if (memberPartitions > limits.maxPartitionMemberships() || managed != (group != null)) throw invalid();
            if (managed) offsets.putAll(group.offsets);
            var member = new ConsumerState(topic, offsets, new ConcurrentHashMap<>(offsets), managed);
            member.touch(System.nanoTime());
            if (members.putIfAbsent(key, member) != null) throw invalid();
        }
        groups.forEach((key, group) -> group.members = members.entrySet().stream()
                .filter(entry -> entry.getValue().managed && entry.getKey().groupId().equals(key.groupId())
                        && entry.getValue().topic.equals(key.topic()))
                .map(entry -> entry.getKey().memberId()).sorted().toList());
        producers.restore(input, (topic, partition) -> topics.containsKey(topic)
                && partition >= 0 && partition < topics.get(topic).partitions().size()
                ? topics.get(topic).partitions().get(partition).nextOffset() : -1);
    }

    private static void offsets(DataOutputStream output, Map<Integer, Long> offsets) throws IOException {
        output.writeInt(offsets.size());
        for (int partition = 0; partition < offsets.size(); partition++) output.writeLong(offsets.get(partition));
    }

    private static Map<Integer, Long> offsets(DataInputStream input, String topic,
                                             Map<String, TopicState> topics) throws IOException {
        var state = topics.get(topic);
        int count = count(input, 1024);
        if (state == null || count != state.partitions().size()) throw invalid();
        Map<Integer, Long> offsets = new ConcurrentHashMap<>();
        for (int partition = 0; partition < count; partition++) {
            long offset = input.readLong();
            if (offset < 0 || offset > state.partitions().get(partition).nextOffset()) throw invalid();
            offsets.put(partition, offset);
        }
        return offsets;
    }

    private static int count(DataInputStream input, int max) throws IOException {
        int value = input.readInt();
        if (value < 0 || value > max) throw invalid();
        return value;
    }
    private static String identity(DataInputStream input) throws IOException {
        String value = input.readUTF();
        if (value.isBlank() || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw invalid();
        return value;
    }
    private static IOException invalid() { return new IOException("Invalid broker checkpoint state"); }
}
