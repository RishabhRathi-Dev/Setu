#!/usr/bin/env python3
"""Observe only Setu FILE workers; retain kernel waits and resource counters."""
import argparse
import datetime
import json
import os
from pathlib import Path
import resource
import time


def read(path):
    try:
        return Path(path).read_text(errors='replace')
    except (OSError, ProcessLookupError):
        return None


def worker_pids():
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            if Path(os.readlink(entry / 'exe')).name != 'java':
                continue
        except OSError:
            continue
        command = read(entry / 'cmdline')
        if command and ('io.setu.benchmark.chaos.NodeTargetThroughputWorker\x00FILE\x00' in command
                        or 'io.setu.storage.log.StorageForceProbe\x00' in command):
            yield entry


def group_path(pid):
    for line in (read(pid / 'cgroup') or '').splitlines():
        if line.startswith('0::'):
            relative = Path(line[3:].lstrip('/'))
            if '..' not in relative.parts:
                return Path('/sys/fs/cgroup') / relative
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stop-file', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    origin = time.monotonic()
    samples = 0
    with (args.output / 'kernel-waits.jsonl').open('w', buffering=1) as stream:
        while not args.stop_file.exists() and time.monotonic() - origin < 1200:
            for pid in worker_pids():
                group = group_path(pid)
                threads = []
                try:
                    tasks = list((pid / 'task').iterdir())
                except OSError:
                    continue
                for task in tasks:
                    wait = (read(task / 'wchan') or '').strip()
                    item = dict(tid=int(task.name), name=(read(task / 'comm') or '').strip(),
                                wait=wait, schedstat=read(task / 'schedstat'))
                    # Capture I/O/reclaim/scheduler waits; routine socket/futex sleepers
                    # have no storage stack to explain and would dominate the recording.
                    if wait and 'futex' not in wait and wait not in (
                            '0', 'ep_poll', 'do_epoll_wait', 'inet_csk_accept', 'hrtimer_nanosleep'):
                        item['stack'] = read(task / 'stack')
                    threads.append(item)
                names = ('cpu.max', 'cpu.stat', 'cpu.pressure', 'memory.current',
                         'memory.events', 'memory.stat', 'memory.pressure', 'io.stat', 'io.pressure')
                usage = resource.getrusage(resource.RUSAGE_SELF)
                row = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                           monotonic=time.monotonic(), pid=int(pid.name),
                           process_io=read(pid / 'io'), threads=threads,
                           cgroup=str(group),
                           counters={name: read(group / name) for name in names} if group else {},
                           host_cpu=read('/proc/stat').splitlines()[0],
                           host_io_pressure=read('/proc/pressure/io'),
                           disks=read('/proc/diskstats'),
                           observer_cpu_seconds=usage.ru_utime + usage.ru_stime)
                stream.write(json.dumps(row, separators=(',', ':')) + '\n')
                samples += 1
            time.sleep(.1)
    print(f'FORCE_WAIT_TRACE samples={samples} elapsed_s={time.monotonic() - origin:.3f}', flush=True)


if __name__ == '__main__':
    main()
