# Java source layout

Each production class, interface, record and enum has its own file, named after the type, in the package that owns it. Implementation helpers stay package-private; test-local fixtures may remain nested.

Use descriptive names for standalone helpers, such as `MemoryLogState` and `GroupCommitState`. Keep them beside their implementations rather than collecting unrelated types in a shared DTO package. See the [architecture guide](architecture.md#module-dependencies) for subsystem responsibilities.

## Migrating public nested types

Update imports using the table below and recompile callers. The former nested names are no longer available, so existing compiled callers must also be rebuilt. Method behavior, wire encoding and persisted formats are unchanged.

| Former name | New name | Package |
| --- | --- | --- |
| `MessageValue.Frame` | `MessageFrame` | `io.setu.storage.log` |
| `MessageValue.Frame.Reader` | `MessageFrameReader` | `io.setu.storage.log` |
| `BufferBudget.Lease` | `BufferLease` | `io.setu.core.protocol` |
| `BufferBudget.Exhausted` | `BufferBudgetExceededException` | `io.setu.core.protocol` |
| `ProtocolCodec.ResponseReader` | `ResponseReader` | `io.setu.core.protocol` |
| `ProtocolCodec.ProduceBatchEncoder` | `ProduceBatchEncoder` | `io.setu.core.protocol` |
| `UringFileIo.Options` | `UringOptions` | `io.setu.storage.uring` |
| `UringFileIo.File` | `UringFile` | `io.setu.storage.uring` |
| `UringFileIo.UnavailableException` | `UringUnavailableException` | `io.setu.storage.uring` |
| `AsyncSetuClient.ConnectionFactory` | `ConnectionFactory` | `io.setu.client` |
| `PostgresOutbox.TransactionHandler` | `TransactionHandler` | `io.setu.client.jdbc` |
| `RequestAuthorizationMetadata.Requirement` | `AuthorizationRequirement` | `io.setu.server.security` |

Create leases with `BufferBudget.reserve`, file handles with `UringFileIo.open`, and immutable frames with `MessageFrame.readFrom` or `MessageFrameReader.finish`.
