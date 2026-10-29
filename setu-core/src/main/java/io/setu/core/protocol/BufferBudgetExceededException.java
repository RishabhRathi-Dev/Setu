package io.setu.core.protocol;

import java.io.IOException;

public final class BufferBudgetExceededException extends IOException {
    public BufferBudgetExceededException(String message) { super(message); }
}
