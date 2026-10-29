# Journal checkpoints and recovery

FILE brokers checkpoint consumer and producer metadata after 64 MiB of journal payloads by default (`journal.compact.bytes`). A one-second maintenance check initiates compaction; this is a trigger, not a hard disk quota. Set zero to disable automatic compaction. Embedding applications can call `DefaultBroker.compactJournal()` explicitly. IN_MEMORY has no journal.

The checkpoint contains active explicit memberships, retained managed groups and offsets, exact assignment generations, and the latest producer/partition epoch, sequence, fingerprint and result offset. It excludes payload history, fetched positions and wall-clock liveness. The current journal tail retains complete transaction payloads until the next successful checkpoint. Topics retain their independent storage/retention policy. Producer and group capacity limits still apply; compaction does not expire identities or weaken fencing.

Request trace context is transient transport metadata. It is stripped from consumer-state and transaction journal records and from producer fingerprints; changing trace IDs between retries does not change the identified operation. Checkpoints do not retain trace parents or vendor state. Journal request records and canonical producer requests use the same protocol with both trace fields empty.

## Publication protocol

1. Acquire the broker transaction write gate, then the broker metadata monitor. This excludes requests and membership maintenance. Completed transactional writes have already forced their output.
2. Write a versioned CRC32C checkpoint in a new `@consumer-state/generation-<uuid>/` directory and force the file.
3. Initialize and force the new Chronicle journal with its checkpoint boundary record. On Unix, force the generation directory.
4. Write and force `CURRENT.tmp`, atomically rename it to `CURRENT`, and on Unix force the parent directory. Filesystems without atomic rename fail compaction.
5. Switch the live journal, close the previous generation, force a permanent `COMPACTED` layout marker, and remove only recognized obsolete journal files. Unrecognized files are preserved. A missing `CURRENT` in a compacted layout fails startup rather than resetting metadata.

The pointer is authoritative: before publication, recovery uses the old journal; afterwards it validates the checkpoint and replays the new tail. A corrupt/missing authoritative checkpoint fails startup rather than falling back to stale progress. Any compaction I/O failure stops broker operations until restart. Orphans from interrupted attempts are reclaimed by the next successful compaction. Peak compaction disk use includes both generations.

Windows uses file forcing and atomic rename, matching the existing retention publication mechanism; Java does not provide directory fsync there. Process-crash recovery is tested. This does not claim protection against faulty storage or establish power-loss behavior on every Windows filesystem. Run deployment-specific durability tests before relying on that broader guarantee.

## Compatibility, backup and operations

Legacy flat `@consumer-state/` journals are read directly until the first checkpoint. After checkpointing, older Setu binaries cannot interpret the generation layout. Do not downgrade the same data directory; restore a compatible offline backup instead. Stop the broker and copy all topic partitions plus the entire `@consumer-state/` directory as one backup. Do not copy only `CURRENT` or only the checkpoint.

`BrokerJournal` owns generation I/O and the root metadata lock; `BrokerCheckpoint` owns the snapshot format. `DefaultBroker` owns lock ordering and semantic validation. Snapshot recovery resets fetched positions to committed progress and restores exact generations without causing an artificial rebalance.

Monitor `setu_journal_bytes`, `setu_journal_compactions_total`, `setu_recovery_seconds` and request latency during compaction. The byte gauge counts logical file sizes, including sparse mapped files, and is refreshed approximately once per second. Checkpoints pause requests while writing bounded metadata; they are not incremental background snapshots.

## Verification

`JournalCompactionTests` covers duplicate retries, epoch fencing, group generations, departed groups, tail replay, repeated reclamation and corruption. Injected failures cover checkpoint writing, checkpoint force, temporary-manifest force, manifest publication, and before/after obsolete-generation cleanup. The [recovery demo](../examples/recovery-demo/README.md) additionally terminates a real broker process after checkpointing and retries the original transaction over TCP. Run the core/integration suites and the demo for changes to this protocol.
