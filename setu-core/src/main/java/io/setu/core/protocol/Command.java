package io.setu.core.protocol;

public enum Command {
    PRODUCE(1), FETCH(2), COMMIT_OFFSET(3), JOIN_GROUP(4), LEAVE_GROUP(5), HEARTBEAT(6), METADATA(7), CREATE_TOPIC(8),
    PRODUCE_BATCH(9), FETCH_OFFSET(10), PRODUCE_IDEMPOTENT(11), TRANSACT(12),
    JOIN_MANAGED_GROUP(13), GROUP_ASSIGNMENT(14), PRODUCE_CONFIGURED(15);

    private final int id;
    private static final Command[] BY_ID;

    static {
        Command[] commands = values();
        int maximum = 0;
        for (Command command : commands) maximum = Math.max(maximum, command.id);
        BY_ID = new Command[maximum + 1];
        for (Command command : commands) BY_ID[command.id] = command;
    }

    Command(int id) { this.id = id; }

    public int id() { return id; }

    public static Command fromId(int id) throws ProtocolException {
        if (id >= 0 && id < BY_ID.length && BY_ID[id] != null) return BY_ID[id];
        throw new ProtocolException(ErrorCode.UNKNOWN_COMMAND, "Unknown command id: " + id);
    }
}
