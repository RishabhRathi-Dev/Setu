package io.setu.core.protocol;

public record RequestOptions(ProducerIdentity producer, ConsumerCommit commit,
                             AcknowledgementMode acknowledgementMode, long generation) {
    public static final RequestOptions DEFAULT = new RequestOptions(null, null, AcknowledgementMode.DURABLE, -1);

    public RequestOptions {
        java.util.Objects.requireNonNull(acknowledgementMode, "acknowledgementMode");
        if (generation < -1) throw new IllegalArgumentException("generation must be -1 or non-negative");
    }
}
