package io.setu.storage.log;

/** Selects volatile process memory or the default durable OpenHFT Chronicle Queue backend. */
public enum StorageMode {
    IN_MEMORY,
    FILE
}
