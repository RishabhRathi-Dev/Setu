package io.setu.core.protocol;

import java.util.List;
import java.util.Map;

public record BrokerResponse(int correlationId, Command command, ErrorCode errorCode, String errorMessage,
                             long offset, List<ProtocolRecord> records, Map<String, Integer> topics) {
    public BrokerResponse {
        if (correlationId < 0) throw new IllegalArgumentException("correlationId must be non-negative");
        if (command == null || errorCode == null) throw new IllegalArgumentException("command and errorCode are required");
        errorMessage = errorMessage == null ? "" : errorMessage;
        records = records == null ? List.of() : List.copyOf(records);
        topics = topics == null ? Map.of() : Map.copyOf(topics);
    }

    public static BrokerResponse ok(int correlationId, Command command) {
        return new BrokerResponse(correlationId, command, ErrorCode.NONE, "", -1, List.of(), Map.of());
    }

    public static BrokerResponse error(int correlationId, Command command, ErrorCode code, String message) {
        return new BrokerResponse(correlationId, command, code, message, -1, List.of(), Map.of());
    }
}
