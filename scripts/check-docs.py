"""Check repository Markdown links, heading anchors and generated README markers."""
from functools import lru_cache
import html
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
LINK = re.compile(r"!?\[[^\]]*\]\((<[^>]+>|[^\s)]+)(?:\s+[^)]*)?\)")


@lru_cache(maxsize=None)
def anchors(path):
    result = set()
    counts = {}
    fenced = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.lstrip().startswith(("```", "~~~")):
            fenced = not fenced
            continue
        if fenced:
            continue
        result.update(re.findall(r'<a\s+(?:id|name)=["\']([^"\']+)', line))
        heading = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not heading:
            continue
        title = html.unescape(re.sub(r"<[^>]+>", "", heading.group(1))).lower()
        slug = re.sub(r"[^\w\- ]", "", title).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if count == 0 else f"{slug}-{count}")
    return result


def check_document(path, root=ROOT):
    errors = []
    fenced = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith(("```", "~~~")):
            fenced = not fenced
            continue
        if fenced:
            continue
        for match in LINK.finditer(line):
            target = match.group(1).strip("<>")
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc:
                continue
            name = unquote(parsed.path)
            destination = (root / name.lstrip("/") if name.startswith("/") else path.parent / name) if name else path
            destination = destination.resolve()
            if not destination.exists():
                errors.append(f"{path.relative_to(root)}:{number}: missing link target: {target}")
            elif parsed.fragment and destination.suffix == ".md" and unquote(parsed.fragment) not in anchors(destination):
                errors.append(f"{path.relative_to(root)}:{number}: missing heading: {target}")
    return errors


def check_markers(path):
    text = path.read_text(encoding="utf-8")
    errors = []
    for marker in ("benchmark-metrics-badges", "benchmark-target-diagnostics", "benchmark-capacity-details"):
        start, end = f"<!-- {marker}:start -->", f"<!-- {marker}:end -->"
        if text.count(start) != 1 or text.count(end) != 1 or text.find(start) >= text.find(end):
            errors.append(f"README.md: expected one ordered start/end pair for {marker}")
    for marker in ("benchmark-redline", "benchmark-contributor-pc"):
        start, end = f"<!-- {marker}:start -->", f"<!-- {marker}:end -->"
        if (start in text or end in text) and (text.count(start) != 1 or text.count(end) != 1 or text.find(start) >= text.find(end)):
            errors.append(f"README.md: expected one ordered start/end pair for {marker}")
    return errors


def main():
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0")
    errors = []
    for name in sorted(set(paths)):
        path = ROOT / name
        if path.suffix == ".md" and path.is_file():
            errors.extend(check_document(path))
    errors.extend(check_markers(ROOT / "README.md"))
    print("\n".join(errors) if errors else "Documentation checks passed")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
