package io.setu.storage.log;

/** Selects whether partition records live only in process memory or in local files. */
public enum StorageMode {
    IN_MEMORY,
    FILE
}
