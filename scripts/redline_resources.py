"""Size exhaustion workers from available memory without changing fixed-target profiles."""
MIB = 1024 ** 2


def transport(heap_mib, record_bytes=1024):
    # Credits bound simultaneous allocations; they do not allocate these buffers eagerly.
    return dict(request_buffer_bytes=max(32 * MIB, heap_mib * MIB // 8),
                response_buffer_bytes=max(32 * MIB, heap_mib * MIB // 8),
                fetch_bytes=min(4 * MIB, 4096 * record_bytes))


def local_budget(processors, available_bytes):
    if type(processors) is not int or processors < 1:
        raise ValueError('Redline requires a positive detected processor count')
    worker_mib = available_bytes // MIB - 768
    if worker_mib < 384:
        raise ValueError('Redline requires memory for the harness and at least 384 MiB for its worker')
    heap = int(worker_mib * .60)
    direct = max(16, int(worker_mib * .10))
    return dict(active_processors=processors, memory_available_bytes=available_bytes,
                heap_mib=heap, direct_mib=direct,
                memory_log_bytes=min(2047, heap // 4) * MIB, **transport(heap))
