package io.setu.core.broker;

/** Bounds active membership and independently bounds retained managed groups and their partition offsets. */
public record ConsumerMetadataLimits(int maxMembers, int maxPartitionMemberships) {
    public ConsumerMetadataLimits {
        if (maxMembers <= 0 || maxPartitionMemberships <= 0)
            throw new IllegalArgumentException("Consumer metadata limits must be positive");
    }

    public static ConsumerMetadataLimits defaults() {
        return new ConsumerMetadataLimits(10_000, 100_000);
    }
}
