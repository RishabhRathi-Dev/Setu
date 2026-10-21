# Managed consumption and transactional writes

Setu's FILE broker supports opt-in managed partition assignment, producer deduplication, and atomic consume-and-produce requests. These features are single-node guarantees. They do not provide replication, failover, cross-broker transactions, or atomic commits with external systems. For PostgreSQL database effects, use the separate [outbox/inbox integration](postgres-outbox.md).

## Managed consumer groups

`joinManagedGroup(group, topic, member)` returns a `GroupAssignment` containing a generation and a map of assigned partition IDs to next committed offsets. Members are sorted by ID; partition `partitionId` belongs to member `partitionId % memberCount`. Join, leave and expiry automatically recalculate assignments and advance the generation. Waiting fetches wake on rebalance. Multiple topics use independent assignments, but a `(group, member)` identity can still register for only one topic.

Query `groupAssignment(group, topic, member)` after a membership change or `STALE_GENERATION`. Pass its generation to the managed `fetch` and `acknowledge` overloads and to transactional input commits. Fetch and commit reject an old generation or a partition owned by another member. Rebalancing clears fetched-but-uncommitted progress; the new owner must fetch from the returned offset. Managed fetches never rewind behind committed group progress.

Group offsets remain after the last member leaves or expires, and recover on restart. A new member resumes those offsets. Existing explicit `joinGroup` memberships retain their previous behavior, including losing progress when they leave or expire. Explicit and managed memberships cannot mix for the same group/topic. Managed groups currently require FILE storage. Fresh groups start at zero; file retention can still cause `OFFSET_OUT_OF_RANGE`.

Send heartbeats while processing or idle. A fresh generation does not make effects outside the broker transactional. Application code must stop work for revoked partitions; a stale owner cannot commit progress or new transactional output, but the broker cannot undo an external API call. Use PostgreSQL inbox transactions to deduplicate database effects when needed.

Durable managed group counts and their summed partition-offset counts are bounded separately by `ConsumerMetadataLimits.maxMembers` and `maxPartitionMemberships`, with the existing defaults of 10,000 / 100,000. Leaving releases active membership capacity, but retained group offsets continue consuming durable group capacity. There is currently no group deletion/compaction API.

## Producer identity and retries

`produceIdempotent(topic, partition, values, new ProducerIdentity(id, epoch, sequence))` writes a nonempty contiguous batch. State is scoped to `(producer ID, topic, partition)`. For each scope, sequences start at zero and increase by one per batch. Keep at most one unresolved batch per scope. Retry uncertain work with exactly the same identity, epoch, sequence and contents; the broker returns the previous first offset without appending again. A different batch or transaction using the same sequence is rejected. Only the latest sequence has a cached result; older sequences are rejected rather than repeated.

A higher caller-supplied epoch fences a lower epoch for that producer/partition and restarts its sequence at zero. Persist and coordinate producer identity, epoch and sequence in the application. Setu does not allocate producer epochs or discover the client's next sequence. Do not advance a sequence or epoch merely because a reply was lost: resolve the original work by retrying it first. If there are multiple producer processes, coordinate epochs outside Setu. Use unique stable producer IDs and do not reuse identities for unrelated writers. A higher epoch intentionally starts new work and does not deduplicate batches from an older epoch.

Producer state recovers from the journal and is bounded to 10,000 producer/partition scopes per broker. There is no expiration or deletion API. Stale epochs return `PRODUCER_FENCED`; sequence gaps and older uncached requests return `OUT_OF_ORDER_SEQUENCE`. Payload mismatch returns `BAD_REQUEST`. A successful retry returns the original offset and does not resurrect output expired by retention. Ordinary `produce`/`produceBatch` requests remain non-idempotent.

## Atomic consume and produce

`transact(outputTopic, outputPartition, values, producerIdentity, inputCommit)` atomically combines a nonempty batch for **one output partition** with advancement of **one input partition's** member/group offset. The input must have been fetched, and the offset must strictly advance. For managed input, include the current assignment generation. Source and destination may be different topics or the same partition. Multi-output-partition transactions, empty output transactions, explicit prepare/abort, and cross-broker transactions are not supported.

```java
var joined = client.joinManagedGroup("enrichment", "orders", "worker-1");
if (joined.errorCode() != ErrorCode.NONE) throw new IllegalStateException(joined.errorMessage());
var assignment = joined.assignment();
for (var entry : assignment.nextOffsets().entrySet()) {
    int partition = entry.getKey();
    var page = client.fetch("enrichment", "worker-1", "orders", partition,
            entry.getValue(), 1024 * 1024, 0, assignment.generation());
    if (page.errorCode() != ErrorCode.NONE) throw new IllegalStateException(page.errorMessage());
    if (page.records().isEmpty()) continue;
    var input = new ConsumerCommit("enrichment", "worker-1", "orders", partition,
            page.records().getLast().offset() + 1, assignment.generation());
    var identity = new ProducerIdentity("worker-1-orders-" + partition, 0, 0);
    var result = client.transact("enriched", partition, List.of(new byte[]{1}), identity, input);
    if (result.errorCode() != ErrorCode.NONE) throw new IllegalStateException(result.errorMessage());
}
```

Create both topics with the required partitions first. The example shows one batch per producer/partition; a running application must maintain sequences and resolve uncertain replies as described above. Use managed groups for shared input progress across worker replacement. Explicit memberships retain independent per-member progress and do not deduplicate consumption between different members.

The broker first validates producer sequencing, input ownership/progress and payload bounds. It drains previously accepted writes on the output partition, then forces one checksummed journal entry containing the request and first output offset. This is the transaction's durable commit decision. It appends/forces the output and advances input progress before returning. Fetch snapshots and offset operations cannot observe the intermediate output/offset state. Long polling releases the transaction gate while waiting, so transactions can wake readers. Ordinary FILE requests share the gate; journaled writes take it exclusively. This adds serialization and extra disk writes for transactional/idempotent paths; it is not a throughput qualification.

After a committed journal entry, storage failure stops further broker operations and maintenance until restart. Recovery replays entries in order, verifies already present output, completes a missing or partial output suffix, restores progress and producer state, and only then serves requests. Conflicting output or malformed committed journal data fails startup. A failed reply may still correspond to a committed transaction; retry its original producer sequence. There is no rollback after the journal commit decision.

`@consumer-state/` now also contains complete transactional/idempotent payloads. Keep it with all partition files in backups. Topic retention does not prune this journal, and journal compaction is not implemented: disk use and restart time grow with history. These capabilities require the upgraded broker; older binaries cannot interpret these journal entries. Preserve a compatible offline backup for rollback.

## Acknowledgement modes

The `produceBatch(topic, partition, values, mode)` overload selects per-request semantics without changing launcher defaults:

| Mode | Successful response |
| --- | --- |
| `DURABLE` | Existing backend completion boundary and first assigned offset; FILE forces touched files before reply, IN_MEMORY publishes the batch |
| `ACCEPTED` | Complete batch admitted to the bounded writer queue; returns offset `-1`, with publication/forcing possibly still pending |

`ACCEPTED` can lose work after success if the process fails before forcing. A later storage failure cannot be reported through its already completed reply; it is logged and future partition requests fail. Queue rejection remains `QUOTA_EXCEEDED` with no records or offsets assigned. Accepted work drains on clean close. IN_MEMORY can publish immediately but still returns `-1` for this mode. Neither mode adds durability to memory storage. Idempotent writes and transactions always require `DURABLE`.

`AsyncSetuClient` uses its configured retry budget for reads, assignment queries, `PRODUCE_IDEMPOTENT` and `TRANSACT`. Retries preserve producer identity and contents. Ordinary and `ACCEPTED` writes are never automatically retried; structured broker errors are never retried. Bound concurrency and sequence each producer/partition's operations explicitly.

## Security and verification

Secure idempotent writes require both output topic `PRODUCE` and exact `producer/<id>` `PRODUCE`. Transactions additionally require input topic `CONSUME` and input group `CONSUMER_GROUP_WRITE`. Managed joins require group `CONSUMER_GROUP_CREATE` plus topic `CONSUME`; assignment queries require group write plus topic consume. These checks run before broker dispatch, including on duplicate retries. Grant producer identities only to their intended principal. Plaintext/embedded broker calls do not authenticate identities.

Run `.\mvnw.cmd -pl setu-integration-tests -am test` from the root. Tests exercise sequence/epoch fencing, membership changes and restart, committed journal recovery with missing/partial/conflicting output, malformed fields, accepted admission/draining, long polling, a lost successful produce reply, and actual TCP/mTLS authorization. Capacity targets still require qualified resource-enforced benchmark evidence.
