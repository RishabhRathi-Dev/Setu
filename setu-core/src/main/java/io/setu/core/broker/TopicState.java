package io.setu.core.broker;

import io.setu.storage.log.MessageLog;
import java.util.List;

record TopicState(List<MessageLog> partitions, List<PartitionProgress> progress) {
    TopicState(List<MessageLog> partitions) {
        this(partitions, partitions.stream().map(ignored -> new PartitionProgress()).toList());
    }
}
