package io.setu.core.logging;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogTextTests {
    @Test void escapesUntrustedLineSeparatorsWithoutLosingTheirMeaning() {
        assertEquals("group\\r\\nforged\\tfield\\u0085\\u2028\\u2029\\\\end",
                LogText.escape("group\r\nforged\tfield\u0085\u2028\u2029\\end"));
        assertNull(LogText.escape(null));
        assertEquals("orders-1", LogText.escape("orders-1"));
    }
}
