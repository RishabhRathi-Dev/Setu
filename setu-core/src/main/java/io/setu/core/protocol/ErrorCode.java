package io.setu.core.protocol;

public enum ErrorCode {
    NONE(0), BAD_REQUEST(1), UNSUPPORTED_VERSION(2), UNKNOWN_COMMAND(3), UNKNOWN_TOPIC(4),
    UNKNOWN_PARTITION(5), AUTHENTICATION_FAILED(6), AUTHORIZATION_DENIED(7), QUOTA_EXCEEDED(8),
    INTERNAL_ERROR(9), OFFSET_OUT_OF_RANGE(10), UNSUPPORTED_OPERATION(11), TOPIC_ALREADY_EXISTS(12),
    STALE_GENERATION(13), PRODUCER_FENCED(14), OUT_OF_ORDER_SEQUENCE(15);

    private final int id;
    ErrorCode(int id) { this.id = id; }
    public int id() { return id; }

    public static ErrorCode fromId(int id) throws ProtocolException {
        for (ErrorCode code : values()) if (code.id == id) return code;
        throw new ProtocolException(BAD_REQUEST, "Unknown error code: " + id);
    }
}
