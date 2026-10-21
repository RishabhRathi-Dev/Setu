package io.setu.core.protocol;

public record ConsumerCommit(String groupId, String memberId, String topic, int partition,
                             long nextOffset, long generation) { }
