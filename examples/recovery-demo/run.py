"""Build and run an isolated recovery demonstration; existing broker data is never touched."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import json
import platform

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--contention", action="store_true", help="run the unqualified mixed-write diagnostic")
    parser.add_argument("--operations", type=int, default=100, help="measured writes per contention writer (1..100000)")
    parser.add_argument("--warmup", type=int, default=20, help="warmup writes per contention writer (0..10000)")
    args = parser.parse_args()
    if not 1 <= args.operations <= 100_000 or not 0 <= args.warmup <= 10_000:
        parser.error("operations must be 1..100000 and warmup must be 0..10000")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else shutil.which("java")
    javac = str(Path(java_home) / "bin" / "javac") if java_home else shutil.which("javac")
    if not java or not javac:
        parser.error("Set JAVA_HOME to JDK 26")
    if not args.skip_build:
        wrapper = [str(ROOT / "mvnw.cmd")] if os.name == "nt" else ["bash", str(ROOT / "mvnw")]
        subprocess.run(wrapper + ["-pl", "setu-server", "-am", "package", "-DskipTests"], cwd=ROOT, check=True)
    base = ROOT / "examples" / "target"
    base.mkdir(exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix="recovery-demo-", dir=base))
    classes = run / "classes"
    classes.mkdir()
    classpath = os.pathsep.join([str(classes), str(ROOT / "setu-server/target/lib/*"), str(ROOT / "setu-server/target/setu-server-1.0.0-SNAPSHOT.jar")])
    name = "MixedWorkloadProbe" if args.contention else "RecoveryDemo"
    subprocess.run([javac, "--release", "26", "-cp", classpath, "-d", str(classes), str(ROOT / f"examples/recovery-demo/{name}.java")], cwd=ROOT, check=True)
    command = [java, "@config/jvm.options", "-Dsetu.log.level=WARN", "-cp", classpath, name]
    command += [str(run), str(args.operations), str(args.warmup)] if args.contention else ["run", str(run)]
    evidence = {
        "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
        "jdk": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True).strip(),
        "host": platform.platform(), "cpu_count": os.cpu_count(), "resource_budget": "unenforced",
        "filesystem_media": "not detected; record separately before making performance comparisons",
        "qualified": False, "repetitions": 1, "command": command,
    }
    (run / "environment.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    try:
        with (run / "result.txt").open("w", encoding="utf-8") as output:
            result = subprocess.run(command, cwd=ROOT, check=False, timeout=300,
                                    stdout=output, stderr=subprocess.STDOUT, text=True)
    finally:
        print((run / "result.txt").read_text(encoding="utf-8", errors="replace"))
        print(f"Evidence: {run}")
    result.check_returncode()


if __name__ == "__main__":
    main()
