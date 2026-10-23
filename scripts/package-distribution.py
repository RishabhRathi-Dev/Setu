"""Package an already-built broker, launchers and documentation with a SHA-256 manifest."""
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def package(root=ROOT):
    version = ET.parse(root / "pom.xml").findtext("{http://maven.apache.org/POM/4.0.0}version")
    jar = root / "setu-server" / "target" / f"setu-server-{version}.jar"
    libraries = sorted((jar.parent / "lib").glob("*.jar"))
    if not jar.is_file() or not libraries:
        raise SystemExit("Build first: ./mvnw -pl setu-server -am package")
    entries = {jar.name: jar, "LICENSE": root / "LICENSE", "README.md": root / "docs/distribution.md"}
    for directory in ("config", "docs"):
        for path in (root / directory).rglob("*"):
            if path.is_file() and not path.is_symlink() and "performance" not in path.parts:
                entries[path.relative_to(root).as_posix()] = path
    for path in libraries:
        entries["lib/" + path.name] = path
    for name in ("start-broker.sh", "start-broker.ps1"):
        entries["scripts/" + name] = root / "scripts" / name
    output = root / "target" / f"setu-{version}.zip"
    output.parent.mkdir(exist_ok=True)
    manifest = []
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, path in sorted(entries.items()):
            data = path.read_bytes()
            manifest.append(f"{hashlib.sha256(data).hexdigest()}  {name}\n")
            info = zipfile.ZipInfo(f"setu-{version}/{name}", (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
            archive.writestr(info, data)
        archive.writestr(zipfile.ZipInfo(f"setu-{version}/SHA256SUMS", (2026, 1, 1, 0, 0, 0)), "".join(manifest))
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n", encoding="utf-8")
    print(output)
    return output


if __name__ == "__main__":
    package()
