"""Build isolated I/O overlays and compare them through Setu's actual TCP worker.

Requires a compiled matching baseline, its captured JVM command, jasyncfio
0.0.8 linux-amd64 and a local C++/liburing toolchain. See README.md.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time

VARIANTS = ["current", "java-direct", "cpp-uring", "java-uring"]
# Balanced order: every variant occupies each position once.
ORDER = [[0, 1, 3, 2], [1, 2, 0, 3], [2, 3, 1, 0], [3, 0, 2, 1]]


def execute(limited, base, log, label, variant, phase):
    """Reap each isolated service, recording the stock Java library's exit defect."""
    marker = "IO_VERIFY " if phase == "verify" else "IO_COMPARISON "
    completed = None
    started = time.monotonic()
    with log.open("w") as output:
        process = subprocess.Popen(limited, cwd=base, stdout=output, stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                now = time.monotonic()
                if completed is None and any(line.startswith(marker) for line in log.read_text().splitlines()):
                    completed = now
                if completed is not None and now - completed > 3:
                    # Both workers print their marker only after all close calls and JFR flush.
                    # Never interpret a missing completion marker or timeout as a successful run.
                    if variant != "java-uring":
                        raise RuntimeError(f"Unexpected shutdown failure: {log}")
                    subprocess.run(["systemctl", "stop", "setu-io-" + label + ".service"], check=True)
                    process.wait(timeout=15)
                    output.write("IO_LIFECYCLE natural_exit=false terminated_after_complete=true\n")
                    return
                if now - started > 240:
                    raise TimeoutError(f"Experiment timed out before completion: {log}")
                time.sleep(0.1)
            if process.returncode:
                raise RuntimeError(f"Experiment exited with {process.returncode}: {log}")
            if not any(line.startswith(marker) for line in log.read_text().splitlines()):
                raise RuntimeError(f"Missing completion marker: {log}")
            output.write("IO_LIFECYCLE natural_exit=true terminated_after_complete=false\n")
        finally:
            if process.poll() is None:
                subprocess.run(["systemctl", "stop", "setu-io-" + label + ".service"], check=False)
                process.wait(timeout=15)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["build", "verify", "smoke", "measure", "report"])
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--base-command", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--tag", default="", help="Prefix for retaining separate verification attempts")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--owned", action="store_true", help="Compare current and Setu-owned FFM io_uring; no external io_uring libraries")
    mode.add_argument("--parity", action="store_true", help="Compare current, jasyncfio and Setu-owned io_uring; no C++ toolchain")
    mode.add_argument("--production", action="store_true", help="Validate the automatic default without storage overlays or backend properties")
    args = parser.parse_args()
    variants_to_run = ["default"] if args.production else ["current", "setu-uring"] if args.owned else ["current", "java-uring", "setu-uring"] if args.parity else VARIANTS
    skip_native_build = args.owned or args.parity or args.production
    external_java = not (args.owned or args.production)
    work, base = args.work.resolve(), args.base.resolve()
    source = Path(__file__).resolve().parent
    classes = work / "classes"
    evidence = work / "evidence"
    evidence.mkdir(exist_ok=True)
    original = json.loads(args.base_command.read_text())
    original = original[:original.index("io.setu.benchmark.chaos.NodeTargetThroughputWorker")]
    cp_index = original.index("-cp") + 1
    old_root = original[cp_index].split(":")[0].split("/setu-benchmark/")[0]
    classpath = original[cp_index].replace(old_root, str(base))
    java_library = work / "vendor/jasyncfio-0.0.8-linux-amd64.jar"
    extras = [str(java_library)] if external_java else []
    cp = ":".join([str(classes), *extras, classpath])
    java = Path(os.environ["JAVA_HOME"]) / "bin/java"
    library = work / "libsetu_uring.so"

    if args.phase == "build":
        classes.mkdir(exist_ok=True)
        overlay = work / "overlay"
        overlay.mkdir(exist_ok=True)
        files = []
        # Refuse to test class files from an older production source revision.
        for path in args.repo.glob("setu-*/src/main/java/**/*.java"):
            relative = path.relative_to(args.repo)
            if not (base / relative).exists() or path.read_bytes() != (base / relative).read_bytes():
                raise ValueError(f"Baseline source differs: {relative}")
        for name in ([] if args.production else ["ChronicleMessageLog", "ChronicleFileSync"]):
            path = args.repo / "setu-storage/src/main/java/io/setu/storage/log" / (name + ".java")
            text = path.read_text()
            if name == "ChronicleMessageLog":
                assert text.count("FileSyncs::open") == 1
                text = text.replace("FileSyncs::open", "ComparisonSync::new")
                assert text.count("FileSyncs.allocatePayload(size)") == 2
                text = text.replace("FileSyncs.allocatePayload(size)",
                                    '(ComparisonSync.VARIANT.equals("current") ? ByteBuffer.allocate(size) : ComparisonSync.VARIANT.equals("setu-uring") ? io.setu.storage.uring.UringFileIo.allocateBuffer(size) : ByteBuffer.allocateDirect(size))')
            else:
                text = text.replace("private final FileChannel channel;", "protected final FileChannel channel;")
            target = overlay / path.name
            target.write_text(text)
            files.append(target)
        for name in ["CapacityJfr", "SustainedThroughputWorker"]:
            text = (args.repo / "setu-benchmark/src/test/java/io/setu/benchmark/chaos" / (name + ".java")).read_text()
            if name == "CapacityJfr" and not args.production:
                text = text.replace('recording.enable("jdk.FileForce").withThreshold(java.time.Duration.ZERO);',
                                    'recording.disable("jdk.FileForce");\n        recording.enable(io.setu.storage.log.ComparisonSync.ForceEvent.class).withThreshold(java.time.Duration.ZERO);')
            elif name == "SustainedThroughputWorker":
                text = text.replace("long cpuStart = OS.getProcessCpuTime();", "long cpuStart = OS.getProcessCpuTime();\n            long[] groupStart = CgroupSample.read();")
                text = text.replace("long cpuEnd = OS.getProcessCpuTime();", "long cpuEnd = OS.getProcessCpuTime();\n            long[] groupEnd = CgroupSample.read();")
                text = text.replace("long backlogEnd = load.backlog();", "long backlogEnd = load.backlog();\n            CgroupSample.report(groupStart, groupEnd, stopped - window.start);")
            target = overlay / (name + ".java")
            target.write_text(text)
            files.append(target)
        compiler = work / "tools/root/usr/bin/gcc"
        native = [str(compiler), "-x", "c++", "-std=c++20", "-O3", "-march=native", "-fPIC",
                  "-fno-exceptions", "-fno-rtti", "-shared", "-Wall", "-Wextra", "-Werror",
                  "-I" + str(work / "tools/root/usr/include"), str(source / "uring_backend.cpp"),
                  "-L" + str(work / "tools/root/usr/lib"), "-Wl,-rpath," + str(work / "tools/root/usr/lib"),
                  "-luring", "-o", str(library)]
        if not skip_native_build:
            subprocess.run(native, check=True)
        for path in source.glob("*.java"):
            if args.production:
                if path.name not in {"VerifyIo.java", "CompareWorker.java", "CompareRecording.java", "CgroupSample.java"}:
                    continue
                if path.name == "VerifyIo.java":
                    target = overlay / path.name
                    target.write_text(path.read_text().replace("new ComparisonSync(", "FileSyncs.open(")
                                      .replace("ComparisonSync.VARIANT", '"default"'))
                    files.append(target)
                    continue
            if args.owned and path.name == "JavaUringBackend.java":
                continue
            if args.owned and path.name == "ComparisonSync.java":
                target = overlay / path.name
                target.write_text(path.read_text().replace('case "java-uring" -> new JavaUringBackend(path, channel);',
                                  'case "java-uring" -> throw new IOException("External backend excluded from owned comparison");'))
                files.append(target)
            else:
                files.append(path)
        compile_java = [str(java.with_name("javac")), "--release", "26", "-proc:none", "-cp", cp,
                        "-d", str(classes), *map(str, files)]
        subprocess.run(compile_java, check=True)
        manifest = {"native_command": None if skip_native_build else native, "java_command": compile_java,
                    "sources": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
                    "library_sha256": None if skip_native_build else hashlib.sha256(library.read_bytes()).hexdigest(),
                    "java_library": str(java_library) if external_java else None,
                    "java_library_sha256": hashlib.sha256(java_library.read_bytes()).hexdigest() if external_java else None,
                    "native_source_sha256": hashlib.sha256((source / "uring_backend.cpp").read_bytes()).hexdigest()}
        (evidence / "build.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print("Built overlays: " + ", ".join(variants_to_run), flush=True)
        return

    if args.phase == "report":
        rows = []
        for variant in variants_to_run:
            trials = []
            for log in sorted(evidence.glob("measure-*-" + variant + ".log")):
                text = log.read_text()
                stages = [line for line in text.splitlines() if line.startswith("SUSTAINED_STAGE ")]
                if len(stages) != 1 or "IO_COMPARISON " not in text:
                    raise ValueError(f"Incomplete capture: {log}")
                stage = dict(field.split("=", 1) for field in stages[0].split()[1:])
                if int(stage["errors"]) or float(stage["produce_per_s"]) <= 0:
                    raise ValueError(f"Execution failure: {log}")
                group = next(line for line in text.splitlines() if line.startswith("IO_CGROUP "))
                stage["cgroup"] = dict(field.split("=", 1) for field in group.split()[1:])
                stage["log"] = str(log)
                stage["natural_exit"] = "IO_LIFECYCLE natural_exit=true " in text
                if variant != "java-uring" and not stage["natural_exit"]:
                    raise ValueError(f"Unexpected lifecycle failure: {log}")
                trials.append(stage)
            if len(trials) != 4:
                raise ValueError(f"Expected four repetitions for {variant}, got {len(trials)}")
            rows.append({"variant": variant, "median_records_per_s": statistics.median(float(t["produce_per_s"]) for t in trials),
                         "mean_records_per_s": statistics.mean(float(t["produce_per_s"]) for t in trials),
                         "min_records_per_s": min(float(t["produce_per_s"]) for t in trials),
                         "max_records_per_s": max(float(t["produce_per_s"]) for t in trials),
                         "median_cgroup_cpu": statistics.median(float(t["cgroup"]["cpu_avg"]) for t in trials),
                         "worst_scheduled_p99_ms": max(float(t["scheduled_p99_ms"]) for t in trials),
                         "natural_exit_all": all(t["natural_exit"] for t in trials), "trials": trials})
        (evidence / "comparison.json").write_text(json.dumps(rows, indent=2) + "\n")
        for row in rows:
            print(json.dumps({key: value for key, value in row.items() if key != "trials"}))
        return

    sys.path.insert(0, str(base / "scripts"))
    spec = importlib.util.spec_from_file_location("benchmark_runner", base / "scripts/run-benchmarks.py")
    runner = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = runner
    spec.loader.exec_module(runner)
    resources = json.loads(next((base / "logs/benchmarks/resources").glob("target-*/resources.json")).read_text())
    budget = runner.Budget(**resources["budget"])
    (evidence / "resources.json").write_text(json.dumps(resources, indent=2) + "\n")
    if args.phase == "measure":
        if args.production:
            order = [variants_to_run] * 4
        elif args.parity:
            order = [[variants_to_run[i] for i in row] for row in [[0, 1, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]]]
        else:
            order = [variants_to_run, variants_to_run[::-1], variants_to_run, variants_to_run[::-1]] if args.owned else [[VARIANTS[i] for i in row] for row in ORDER]
    else:
        order = [variants_to_run]
    for repeat, variants in enumerate(order, 1):
        for variant in variants:
            label = f"{args.phase}-{repeat}-{variant}"
            if args.tag:
                label = args.tag + "-" + label
            log, capture = evidence / (label + ".log"), evidence / (label + ".jfr")
            if log.exists():
                raise FileExistsError(log)
            data = work / "data" / label
            data.mkdir(parents=True)
            command = [arg for arg in original if not arg.startswith(("-DSETU_LOG_DIR=", "-Djava.io.tmpdir=", "-Dsetu.benchmark.stage.millis=", "-Dsetu.benchmark.warmup.millis=", "-Dsetu.benchmark.measurement="))]
            command[0] = str(java)
            command[command.index("-cp") + 1] = cp
            command[1:1] = ["-Djava.io.tmpdir=" + str(work / "data"), "-DSETU_LOG_DIR=" + str(evidence)]
            if not args.production:
                command.insert(1, "-Dsetu.io.variant=" + variant)
            if variant == "cpp-uring":
                command.insert(1, "-Dsetu.io.library=" + str(library))
            if args.phase == "verify":
                command += ["-Xmx512m", "-XX:MaxDirectMemorySize=256m", "io.setu.storage.log.VerifyIo", "verify", str(data)]
            else:
                warmup, stage, measurement = ((10000, 60000 if args.production else 30000, "full")
                                              if args.phase == "measure" else (2000, 3000, "smoke"))
                command += [f"-Dsetu.benchmark.warmup.millis={warmup}", f"-Dsetu.benchmark.stage.millis={stage}",
                            "-Dsetu.benchmark.measurement=" + measurement, "io.setu.benchmark.chaos.CompareWorker", str(data), str(capture)]
            limited = runner.limited_command(command, budget, "setu-io-" + label)
            (evidence / (label + "-command.json")).write_text(json.dumps(limited, indent=2) + "\n")
            print("START " + label, flush=True)
            execute(limited, base, log, label, variant, args.phase)
            for line in log.read_text().splitlines():
                if line.startswith(("IO_VERIFY", "SUSTAINED_STAGE", "IO_CGROUP", "IO_LIFECYCLE")):
                    print(line, flush=True)
            if args.phase != "verify":
                analysis = subprocess.check_output([str(java), "-cp", cp, "io.setu.benchmark.chaos.CompareRecording", str(capture),
                                                    *( ["production"] if args.production else [])], text=True)
                (evidence / (label + "-forces.log")).write_text(analysis)
                print(analysis, end="", flush=True)


if __name__ == "__main__":
    main()
