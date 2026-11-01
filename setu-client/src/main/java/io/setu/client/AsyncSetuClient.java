package io.setu.client;

import io.setu.core.protocol.BrokerRequest;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.Command;
import io.setu.core.protocol.ErrorCode;

import java.io.IOException;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class AsyncSetuClient implements AutoCloseable {

    private final ConnectionFactory connectionFactory;
    private final ThreadPoolExecutor executor;
    private final int readRetries;
    private final Semaphore submissions;
    private final ThreadLocal<SetuClient> local = new ThreadLocal<>();
    private final Set<SetuClient> connections = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<BrokerResponse>> pending = ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, Integer> partitionCounts = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public AsyncSetuClient(ConnectionFactory connectionFactory, int connectionCount, int queuedRequests, int readRetries) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        if (connectionCount < 1 || connectionCount > 1024 || queuedRequests < 1 || readRetries < 0 || readRetries > 10)
            throw new IllegalArgumentException("connections must be 1..1024, queue positive, and readRetries 0..10");
        this.readRetries = readRetries;
        submissions = new Semaphore(connectionCount + queuedRequests);
        executor = new ThreadPoolExecutor(connectionCount, connectionCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queuedRequests), Thread.ofVirtual().name("setu-async-", 0).factory());
    }

    public CompletableFuture<BrokerResponse> request(BrokerRequest request) {
        Objects.requireNonNull(request, "request");
        return submit(() -> client -> client.request(request), retryable(request.command()));
    }

    private CompletableFuture<BrokerResponse> submit(Supplier<ClientOperation> prepare, boolean retryable) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Client is closed"));
        // Reserve before taking a payload snapshot: overload must not copy batches that cannot be queued.
        if (!submissions.tryAcquire())
            return CompletableFuture.failedFuture(new RejectedExecutionException("Client queue is full"));
        ClientOperation operation;
        try { operation = prepare.get(); }
        catch (RuntimeException | Error failure) { submissions.release(); throw failure; }
        var submissionContext = io.opentelemetry.context.Context.current();
        var future = new CompletableFuture<BrokerResponse>();
        pending.add(future);
        future.whenComplete((response, failure) -> pending.remove(future));
        if (closed) {
            submissions.release();
            future.completeExceptionally(new IOException("Client is closed"));
            return future;
        }
        try {
            executor.execute(() -> {
                try (var scope = submissionContext.makeCurrent()) {
                    if (!future.isDone()) future.complete(exchange(operation, retryable));
                }
                catch (IOException | RuntimeException failure) { future.completeExceptionally(failure); }
                finally { submissions.release(); }
            });
        } catch (RejectedExecutionException failure) {
            submissions.release();
            future.completeExceptionally(failure);
        }
        return future;
    }

    public CompletableFuture<BrokerResponse> produce(String topic, int partition, byte[] value) {
        return submit(() -> {
            var request = new BrokerRequest(0, Command.PRODUCE, topic, partition, -1, -1, value, 0, null, null);
            return client -> client.request(request);
        }, false);
    }

    /** Copies the batch before scheduling and returns its first offset. Ordinary writes are never retried. */
    public CompletableFuture<BrokerResponse> produceBatch(String topic, int partition, List<byte[]> values) {
        return submit(() -> {
            List<byte[]> snapshot = List.copyOf(values).stream().map(byte[]::clone).toList();
            return client -> client.produceBatch(topic, partition, snapshot);
        }, false);
    }

    public CompletableFuture<BrokerResponse> produceIdempotent(String topic, int partition,
            java.util.List<byte[]> values, io.setu.core.protocol.ProducerIdentity producer) {
        return request(new BrokerRequest(0, Command.PRODUCE_IDEMPOTENT, topic, partition, -1, -1,
                null, 0, null, null, values).withOptions(new io.setu.core.protocol.RequestOptions(
                producer, null, io.setu.core.protocol.AcknowledgementMode.DURABLE, -1)));
    }

    public CompletableFuture<BrokerResponse> transact(String topic, int partition, java.util.List<byte[]> values,
            io.setu.core.protocol.ProducerIdentity producer, io.setu.core.protocol.ConsumerCommit commit) {
        return request(new BrokerRequest(0, Command.TRANSACT, topic, partition, -1, -1,
                null, 0, null, null, values).withOptions(new io.setu.core.protocol.RequestOptions(
                producer, commit, io.setu.core.protocol.AcknowledgementMode.DURABLE, -1)));
    }

    public CompletableFuture<BrokerResponse> produce(String topic, String key, byte[] value) {
        Objects.requireNonNull(key, "key");
        var payload = io.setu.storage.log.MessageValue.copyOf(value);
        Integer knownCount = partitionCounts.get(topic);
        if (knownCount != null) return produceRouted(topic, key, payload, knownCount);
        var metadata = new BrokerRequest(0, Command.METADATA, null, -1, -1, -1, null, 0, null, null);
        return request(metadata).thenCompose(response -> {
            if (response.errorCode() != ErrorCode.NONE)
                return CompletableFuture.completedFuture(new BrokerResponse(0, Command.PRODUCE,
                        response.errorCode(), response.errorMessage(), -1, java.util.List.of(), java.util.Map.of()));
            Integer count = response.topics().get(topic);
            if (count == null)
                return CompletableFuture.completedFuture(BrokerResponse.error(0, Command.PRODUCE,
                        ErrorCode.UNKNOWN_TOPIC, "Unknown topic: " + topic));
            partitionCounts.putAll(response.topics());
            return produceRouted(topic, key, payload, count);
        });
    }

    private CompletableFuture<BrokerResponse> produceRouted(String topic, String key,
                                                            io.setu.storage.log.MessageValue payload, int count) {
        return request(new BrokerRequest(0, Command.PRODUCE, topic, Math.floorMod(key.hashCode(), count),
                -1, -1, payload, 0, null, null, java.util.List.of(), 0));
    }

    private static boolean retryable(Command command) {
        return command == Command.METADATA || command == Command.FETCH || command == Command.FETCH_OFFSET
                || command == Command.GROUP_ASSIGNMENT || command == Command.PRODUCE_IDEMPOTENT
                || command == Command.TRANSACT;
    }

    private BrokerResponse exchange(ClientOperation operation, boolean retryable) throws IOException {
        for (int attempt = 0; ; attempt++) {
            if (closed) throw new IOException("Client is closed");
            SetuClient client = local.get();
            try {
                if (client == null) {
                    client = Objects.requireNonNull(connectionFactory.connect(), "connected client");
                    connections.add(client);
                    local.set(client);
                    if (closed) throw new IOException("Client is closed");
                }
                return operation.exchange(client);
            } catch (IOException failure) {
                if (client != null) {
                    try { discard(client); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                if (!retryable || attempt >= readRetries) throw failure;
            } catch (RuntimeException failure) {
                if (client != null) {
                    try { discard(client); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }
    }

    private void discard(SetuClient client) throws IOException {
        local.remove();
        connections.remove(client);
        client.close();
    }

    @Override public void close() throws IOException {
        closed = true;
        executor.shutdownNow();
        pending.forEach(future -> future.completeExceptionally(new IOException("Client is closed")));
        IOException failure = null;
        for (SetuClient client : connections) {
            try { client.close(); }
            catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        connections.clear();
        if (failure != null) throw failure;
    }
}
