"""Verify a built Linux image using isolated, disposable Docker resources."""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def docker(*args, timeout=180):
    result = subprocess.run(["docker", *args], cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"docker {' '.join(args)} failed:\n{result.stdout}{result.stderr}")
    return result.stdout + result.stderr if args[0] == "logs" else result.stdout


def inspect(container):
    return json.loads(docker("inspect", container))[0]


def wait_healthy(container, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = inspect(container)["State"]
        if state.get("Health", {}).get("Status") == "healthy":
            return
        if not state["Running"] or state.get("Health", {}).get("Status") == "unhealthy":
            raise RuntimeError(f"Broker failed readiness: {state}")
        time.sleep(2)
    raise TimeoutError("Broker did not become healthy")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="setu:local")
    parser.add_argument("--output", type=Path, default=ROOT / "target" / "container-smoke",
                        help="Parent directory for a fresh evidence directory per run")
    args = parser.parse_args()
    name = "setu-smoke-" + uuid.uuid4().hex
    output_directory = args.output / name
    output_directory.mkdir(parents=True)
    print(f"Container verification evidence: {output_directory}")
    volume = name + "-data"
    created_volume = False
    created_container = False
    try:
        docker("volume", "create", volume)
        created_volume = True
        docker("create", "--name", name, "--read-only", "--cpus", "2", "--memory", "5g",
               "--memory-swap", "5g", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
               "--tmpfs", "/tmp:rw,nosuid,size=128m,mode=1777", "--tmpfs", "/var/log/setu:uid=10001,gid=10001",
               "--volume", f"{volume}:/var/lib/setu", "--publish", "127.0.0.1::9092", args.image)
        created_container = True
        docker("start", name)
        wait_healthy(name)
        details = inspect(name)
        host = details["HostConfig"]
        if details["Config"]["User"] != "10001:10001" or not host["ReadonlyRootfs"]:
            raise RuntimeError("Image must run as its unprivileged user with a read-only root")
        if (host["NanoCpus"], host["Memory"], host["MemorySwap"]) != (2_000_000_000, 5 * 1024**3, 5 * 1024**3):
            raise RuntimeError("Container resource limits differ from the rounded CI baseline")
        binding = details["NetworkSettings"]["Ports"]["9092/tcp"][0]
        with socket.create_connection((binding["HostIp"], int(binding["HostPort"])), timeout=5):
            pass
        for phase in ("write", "read"):
            if phase == "read":
                # Stop/restart the same persistent data, allowing Java's shutdown hook to drain writes.
                docker("stop", "--time", "60", name)
                stopped = inspect(name)["State"]
                if stopped["OOMKilled"] or stopped["ExitCode"] not in (0, 143):
                    raise RuntimeError(f"Broker failed graceful shutdown: {stopped}")
                docker("start", name)
                wait_healthy(name)
            docker("cp", str(ROOT / "docker" / "ContainerSmoke.java"), f"{name}:/tmp/ContainerSmoke.java")
            output = docker("exec", name, "java", "@/opt/setu/config/jvm.options", "-cp", "/opt/setu/lib/*",
                            "/tmp/ContainerSmoke.java", phase)
            (output_directory / f"{phase}.log").write_text(output, encoding="utf-8")
            print(output.strip())
        (output_directory / "result.txt").write_text("Container startup, client exchange, shutdown and FILE recovery passed\n", encoding="utf-8")
    finally:
        try:
            if created_container:
                try:
                    (output_directory / "broker.log").write_text(docker("logs", name), encoding="utf-8")
                    (output_directory / "inspect.json").write_text(docker("inspect", name), encoding="utf-8")
                finally:
                    docker("rm", "--force", name)
        finally:
            if created_volume:
                docker("volume", "rm", volume)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
