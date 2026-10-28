# FILE I/O

FILE storage uses Chronicle Queue with platform-specific payload writes and
durability barriers. Backend selection is automatic; no backend selector,
native-library path or ring-tuning property is required. Use JDK 26 and the
shared [JVM options](../config/jvm.options), including native access.

| Platform | Payload writes | Durability barrier |
| --- | --- | --- |
| Supported Linux | Setu-owned Panama/io_uring with owned direct buffers | JDK full metadata force on first use/growth, then io_uring data force |
| Linux with unavailable ring setup | Positional FileChannel writes | JDK full metadata force on first use/growth, then data force |
| Windows and other platforms | Buffered payloads copied into Chronicle's mappings | Force mapped contents, then full FileChannel force |

The record format, CRC, dense offsets and committed boundary are the same on
every platform. Durable success follows forcing all touched queue, index,
header and roll-metadata files. Close drains accepted writes. See the
[storage guide](../setu-storage/README.md) for recovery and ownership contracts.

## Linux backend

`io.setu.storage.uring.UringFileIo` uses Java 26's Foreign Function and Memory API
to call Linux system calls and libc. It requires no jasyncfio, JNI shim, C++
build, liburing installation or additional Maven dependency. Its ABI supports
little-endian Linux x86-64 and AArch64; native validation currently covers x86-64
under WSL2. AArch64 has not been exercised on hardware.

If ring setup is unsupported or denied (EPERM, EACCES, ENOSYS, EOPNOTSUPP), the
broker logs one warning and uses FileChannel for the process. Other setup errors,
including resource exhaustion, fail explicitly. Once a ring is acquired,
open/write/force failures propagate without retrying through another backend.

One non-daemon platform thread owns the shared ring, mappings and descriptors.
Existing virtual partition writers submit work and await private completion
futures. Calls serialize per file; independent files can remain in flight and
complete independently. An MPSC queue carries submissions; producers coalesce
eventfd wakeups, and the owner drains ready work before sleeping. There is no
SQPOLL, IOPOLL, O_DIRECT or busy idle loop. The last file close drains and joins
the owner; reopening later starts a fresh owner.

The architecture takes inspiration from
[jasyncfio](https://github.com/ikorennoy/jasyncfio), inspected at Apache-2.0
revision `165fe7f6a5a5f7ade45b33872e8e6207f99ecc17`. Setu's implementation is
independently written against the Linux UAPI; upstream sources and binaries
are not bundled. Setu's [Apache-2.0 licence](../LICENSE) applies.

## Windows backend

Windows uses the same batched payload encoding, virtual partition writers,
backpressure and durable acknowledgement contract. It keeps payload writes in
Chronicle's mappings: Microsoft does not guarantee coherence between mapped
views and ordinary file I/O. A Windows I/O-ring write cannot therefore replace
the Linux write path without changing how Chronicle accesses the file.
[Mapped-view coherence](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-mapviewoffile).

Windows requires flushing dirty mapped pages and then file buffers for durable
storage. The current backend uses scoped JDK mappings followed by full
FileChannel force. The scopes release each extra mapping before the next force,
including when another descriptor grows the file.
[FlushViewOfFile contract](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-flushviewoffile).

A local Java FFM experiment confirmed Windows 11 ring write/flush support, but
substituting ring flushes while preserving mapped writes showed similar rates
with no consistent advantage. Windows retains its existing backend; no experimental Windows
native dependency or configuration is shipped. See the
[measurements and limitations](file-io-benchmarks.md#windows-flush-experiment).

## Ownership and bounds

Linux payload staging uses `UringFileIo.allocateBuffer(capacity)`. These are
GC-managed JDK direct buffers whose scopes remain eligible through slices and
read-only views. The native owner retains them until kernel completion, avoiding
an extra staging copy. A weak scope registry does not retain unused allocations.
Heap buffers use native scratch slots. Other direct buffers, including confined
arenas, are copied on the caller before submission so an exceptional return
cannot expose freed memory to an unresolved request. Callers must keep contents
unchanged until the call returns.

| Bound | Broker default |
| --- | ---: |
| Ring entries | 16, including one wakeup poll |
| Write chunk | 1 MiB |
| Outstanding requests | 1,024 |
| Outstanding payload references | 32 MiB |
| Native scratch | 16 MiB, plus ring mappings and small wakeup buffers |
| Retained payload staging per log | At most 1 MiB; larger records use temporary buffers |

Admission includes queued and active work. The broker waits for request/byte
credit; explicit standalone `create` calls retain fail-fast admission. Native
scratch is outside `MaxDirectMemorySize` but inside process/cgroup memory usage.
Owned direct source buffers count toward the JDK direct-memory limit. Retained
views can keep larger backing allocations alive than their submitted range.
These bounds are not a whole-process memory limit.

Accepted operations finish despite interruption, then restore the caller's
interrupt flag. File close waits for earlier operations; owner close rejects
new work, drains admitted work and joins its thread. Close is idempotent. A
blocked device can delay shutdown. Failed writes/forces poison that file until
close/reopen. Short writes are retried; zero progress and negative completions
fail, and a large write may have a completed prefix on failure.

Completions are matched by unique identifiers using release/acquire publication.
A fatal submission/protocol failure stops the executor and fails queued callers.
If kernel requests remain unresolved, their ring, descriptors and buffers are
retained until process exit to prevent use-after-free. This exceptional state
is reported as a close failure and requires a process restart.

## Standalone API

The standalone API opens existing files; it never creates or truncates them.
Explicit creation fails on unsupported/denied systems. Automatic fallback is a
broker policy, and `isSupportedPlatform()` checks only OS/architecture.

```java
import io.setu.storage.uring.UringFileIo;
import java.nio.ByteBuffer;
import java.nio.file.Path;

try (var io = UringFileIo.create();
     var file = io.open(Path.of("existing-file"))) {
    ByteBuffer data = UringFileIo.allocateBuffer(4096);
    data.putLong(42).flip();
    file.writeFully(data, 0);
    file.force(true);
}
```

`force(true)` performs fsync and `force(false)` performs fdatasync. Writes alone
are not durable. Callers own file creation/directory durability; close adds no
implicit force. Virtual threads may call this synchronous API directly. Private
completion futures cannot be cancelled externally. `Options` supports bounded
standalone configuration with a 64 MiB native-scratch ceiling; broker defaults
are fixed internals, not server properties.

## Verification

Run portable storage tests with `./mvnw.cmd -pl setu-storage test`. Native Linux
coverage is opt-in and fails on unsupported/denied kernels:

```bash
bash ./mvnw -pl setu-integration-tests -am -Dsetu.uring.tests=true test
```

Coverage includes admission, independent completion, interruption, shutdown,
startup/fatal errors, mapped headers, growth, CRC/readback and recovery. Native
barriers emit `io.setu.storage.FileForce`; benchmark recordings combine these
with `jdk.FileForce` metadata barriers without double counting.

The [benchmark report](file-io-benchmarks.md) records results and qualification
limits. The [comparison harness](../scripts/diagnostics/io-comparison/README.md)
provides `--production`, `--owned` and `--parity` modes. Single-load comparisons
do not establish sustained capacity or change published badges.

ABI references: [Linux io_uring UAPI](https://github.com/torvalds/linux/blob/master/include/uapi/linux/io_uring.h),
[setup](https://man7.org/linux/man-pages/man2/io_uring_setup.2.html),
[submission/completion](https://man7.org/linux/man-pages/man2/io_uring_enter.2.html).
