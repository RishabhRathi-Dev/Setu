package io.setu.storage.log;

import java.util.List;
import java.util.concurrent.CompletableFuture;

record PendingAppend(List<MessageValue> values, long bytes, CompletableFuture<Long> result) { }
