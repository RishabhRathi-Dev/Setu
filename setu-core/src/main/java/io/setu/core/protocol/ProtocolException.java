package io.setu.core.protocol;

import java.io.IOException;

public final class ProtocolException extends IOException {
    private final ErrorCode errorCode;
    private final int correlationId;

    public ProtocolException(ErrorCode errorCode, String message) {
        this(errorCode, message, -1);
    }

    public ProtocolException(ErrorCode errorCode, String message, int correlationId) {
        super(message);
        this.errorCode = errorCode;
        this.correlationId = correlationId;
    }

    public ErrorCode errorCode() { return errorCode; }
    public int correlationId() { return correlationId; }
}
