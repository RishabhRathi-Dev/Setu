package io.setu.core.broker;

import io.setu.core.protocol.ErrorCode;

public final class BrokerException extends RuntimeException {
    private final ErrorCode errorCode;

    public BrokerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BrokerException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
