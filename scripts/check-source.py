"""Dependency-free formatting gate for tracked Java, Python, shell and configuration source."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = {".java", ".py", ".sh", ".xml", ".yml", ".yaml", ".properties", ".ps1"}


def main():
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0")
    errors = []
    for name in sorted(set(paths)):
        path = ROOT / name
        if not name or path.suffix not in SUFFIXES or not path.is_file():
            continue
        data = path.read_bytes()
        text = data.decode("utf-8")
        if text and not text.endswith("\n"):
            errors.append(f"{name}: missing final newline")
        if path.suffix == ".sh" and b"\r" in data:
            errors.append(f"{name}: shell scripts must use LF")
        for line, value in enumerate(text.splitlines(), 1):
            if "\t" in value or value.rstrip() != value:
                errors.append(f"{name}:{line}: tabs or trailing whitespace")
    print("\n".join(errors) if errors else "Source formatting checks passed")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
