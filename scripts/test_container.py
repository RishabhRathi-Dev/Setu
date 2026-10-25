"""Check container verification failure handling without requiring Docker Engine."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location("verify_container", Path(__file__).with_name("verify-container.py"))
CONTAINER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTAINER)


class ContainerVerificationTests(unittest.TestCase):
    def test_readiness_failure_retains_evidence_and_removes_only_its_resources(self):
        commands = []

        def docker(*args, **kwargs):
            commands.append(args)
            if args[0] == "inspect":
                return json.dumps([{"State": {"Running": False, "Health": {"Status": "unhealthy"}}}])
            return "broker evidence\n"

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(CONTAINER, "docker", side_effect=docker), \
             patch.object(sys, "argv", ["verify-container", "--output", directory]):
            with self.assertRaisesRegex(RuntimeError, "failed readiness"):
                CONTAINER.main()
            evidence = next(Path(directory).iterdir())
            self.assertEqual("broker evidence\n", (evidence / "broker.log").read_text())
            self.assertTrue((evidence / "inspect.json").is_file())
        created_name = next(args[2] for args in commands if args[0] == "create")
        self.assertTrue(created_name.startswith("setu-smoke-"))
        self.assertEqual(("rm", "--force", created_name), commands[-2])
        self.assertEqual(("volume", "rm", created_name + "-data"), commands[-1])

    def test_failed_container_creation_still_removes_created_volume(self):
        commands = []

        def docker(*args, **kwargs):
            commands.append(args)
            if args[0] == "create":
                raise RuntimeError("image not found")
            return ""

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(CONTAINER, "docker", side_effect=docker), \
             patch.object(sys, "argv", ["verify-container", "--output", directory]):
            with self.assertRaisesRegex(RuntimeError, "image not found"):
                CONTAINER.main()
        self.assertEqual("volume", commands[-1][0])
        self.assertEqual("rm", commands[-1][1])
        self.assertFalse(any(args[0] == "rm" for args in commands))

    def test_client_failure_aborts_verification_and_keeps_evidence(self):
        commands = []
        state = {"Running": True, "Health": {"Status": "healthy"}}
        details = {"State": state, "Config": {"User": "10001:10001"},
                   "HostConfig": {"ReadonlyRootfs": True, "NanoCpus": 2_000_000_000,
                                  "Memory": 5 * 1024**3, "MemorySwap": 5 * 1024**3},
                   "NetworkSettings": {"Ports": {"9092/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49152"}]}}}

        def docker(*args, **kwargs):
            commands.append(args)
            if args[0] == "inspect":
                return json.dumps([details])
            if args[0] == "exec" and args[2] == "java":
                raise RuntimeError("broker returned error")
            return "evidence\n"

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(CONTAINER, "docker", side_effect=docker), \
             patch.object(CONTAINER.socket, "create_connection", return_value=MagicMock()), \
             patch.object(sys, "argv", ["verify-container", "--output", directory]):
            with self.assertRaisesRegex(RuntimeError, "broker returned error"):
                CONTAINER.main()
            evidence = next(Path(directory).iterdir())
            self.assertFalse((evidence / "result.txt").exists())
            self.assertTrue((evidence / "broker.log").is_file())
        self.assertEqual("rm", commands[-2][0])
        self.assertEqual(("volume", "rm"), commands[-1][:2])

    def test_docker_failure_includes_diagnostics_and_preserves_argument_boundaries(self):
        result = subprocess.CompletedProcess([], 1, "output", "failure")
        with patch.object(CONTAINER.subprocess, "run", return_value=result) as run:
            with self.assertRaisesRegex(RuntimeError, "outputfailure"):
                CONTAINER.docker("exec", "-i", "container", "sh", "-c", "cat > /tmp/source.java",
                                 input_text="Java source\n")
            self.assertEqual(["docker", "exec", "-i", "container", "sh", "-c", "cat > /tmp/source.java"],
                             run.call_args.args[0])
            self.assertEqual("Java source\n", run.call_args.kwargs["input"])
            self.assertEqual("utf-8", run.call_args.kwargs["encoding"])

    def test_source_is_streamed_to_tmpfs_again_after_restart_with_read_only_root(self):
        commands = []
        phases = []
        uploaded = False
        state = {"Running": True, "Health": {"Status": "healthy"}, "OOMKilled": False, "ExitCode": 0}
        details = {"State": state, "Config": {"User": "10001:10001"},
                   "HostConfig": {"ReadonlyRootfs": True, "NanoCpus": 2_000_000_000,
                                  "Memory": 5 * 1024**3, "MemorySwap": 5 * 1024**3},
                   "NetworkSettings": {"Ports": {"9092/tcp": [{"HostIp": "127.0.0.1", "HostPort": "49152"}]}}}

        def docker(*args, **kwargs):
            nonlocal uploaded
            commands.append(args)
            if args[0] == "cp":
                raise RuntimeError("container rootfs is marked read-only")
            if args[0] == "inspect":
                return json.dumps([details])
            if args[0] == "stop":
                uploaded = False
                state["Running"] = False
            if args[0] == "start":
                state["Running"] = True
            if args[:2] == ("exec", "-i"):
                self.assertEqual(("sh", "-c", "cat > /tmp/ContainerSmoke.java"), args[3:])
                self.assertEqual((CONTAINER.ROOT / "docker/ContainerSmoke.java").read_text(encoding="utf-8"),
                                 kwargs["input_text"])
                uploaded = True
            elif args[0] == "exec":
                self.assertTrue(uploaded, "The tmpfs source must be restored after each start")
                self.assertEqual("/tmp/ContainerSmoke.java", args[-2])
                phases.append(args[-1])
            return "evidence\n"

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(CONTAINER, "docker", side_effect=docker), \
             patch.object(CONTAINER.socket, "create_connection", return_value=MagicMock()), \
             patch.object(sys, "argv", ["verify-container", "--output", directory]):
            self.assertEqual(0, CONTAINER.main())
            evidence = next(Path(directory).iterdir())
            self.assertIn("FILE recovery passed", (evidence / "result.txt").read_text())
        create = next(args for args in commands if args[0] == "create")
        self.assertIn("--read-only", create)
        self.assertIn("/tmp:rw,nosuid,size=128m,mode=1777", create)
        self.assertEqual(["write", "read"], phases)
        self.assertEqual(2, sum(args[:2] == ("exec", "-i") for args in commands))


if __name__ == "__main__":
    unittest.main()
