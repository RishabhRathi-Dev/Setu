package io.setu.core.protocol;

public enum ErrorCode {
    NONE(0), BAD_REQUEST(1), UNKNOWN_COMMAND(3), UNKNOWN_TOPIC(4),
    UNKNOWN_PARTITION(5), AUTHENTICATION_FAILED(6), AUTHORIZATION_DENIED(7), QUOTA_EXCEEDED(8),
    INTERNAL_ERROR(9), OFFSET_OUT_OF_RANGE(10), UNSUPPORTED_OPERATION(11), TOPIC_ALREADY_EXISTS(12),
    STALE_GENERATION(13), PRODUCER_FENCED(14), OUT_OF_ORDER_SEQUENCE(15);

    private final int id;
    private static final ErrorCode[] BY_ID;

    static {
        ErrorCode[] codes = values();
        int maximum = 0;
        for (ErrorCode code : codes) maximum = Math.max(maximum, code.id);
        BY_ID = new ErrorCode[maximum + 1];
        for (ErrorCode code : codes) BY_ID[code.id] = code;
    }
    ErrorCode(int id) { this.id = id; }
    public int id() { return id; }

    public static ErrorCode fromId(int id) throws ProtocolException {
        if (id >= 0 && id < BY_ID.length && BY_ID[id] != null) return BY_ID[id];
        throw new ProtocolException(BAD_REQUEST, "Unknown error code: " + id);
    }
}
