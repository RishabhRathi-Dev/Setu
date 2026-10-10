package io.setu.core.protocol;

public enum Command {
    PRODUCE(1), FETCH(2), COMMIT_OFFSET(3), JOIN_GROUP(4), LEAVE_GROUP(5), HEARTBEAT(6), METADATA(7), CREATE_TOPIC(8),
    PRODUCE_BATCH(9);

    private final int id;

    Command(int id) { this.id = id; }

    public int id() { return id; }

    public static Command fromId(int id) throws ProtocolException {
        for (Command command : values()) {
            if (command.id == id) return command;
        }
        throw new ProtocolException(ErrorCode.UNKNOWN_COMMAND, "Unknown command id: " + id);
    }
}
