package io.setu.core.protocol;

/** Bounded transport metadata, separate from message payloads and producer identity. */
public record TraceContext(String traceparent, String tracestate) {
    public static final int MAX_FIELD_BYTES = 512;
    public static final TraceContext NONE = new TraceContext("", "");

    public TraceContext {
        traceparent = traceparent == null ? "" : traceparent;
        tracestate = tracestate == null ? "" : tracestate;
        validate(traceparent);
        validate(tracestate);
        if (traceparent.isEmpty() && !tracestate.isEmpty())
            throw new IllegalArgumentException("tracestate requires traceparent");
    }

    public boolean isEmpty() { return traceparent.isEmpty(); }

    private static void validate(String value) {
        if (value.length() > MAX_FIELD_BYTES) throw new IllegalArgumentException("Trace field exceeds 512 bytes");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 0x20 || value.charAt(i) > 0x7e)
                throw new IllegalArgumentException("Trace fields must contain printable ASCII");
    }
}
