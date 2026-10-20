package io.setu.core.logging;

/** Renders untrusted text as one log field without changing its protocol value. */
public final class LogText {
    private LogText() { }

    public static String escape(String value) {
        if (value == null) return null;
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("\t", "\\t").replace("\u0085", "\\u0085")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
