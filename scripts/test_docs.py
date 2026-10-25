"""Behavioral checks for repository Markdown validation."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("check_docs", Path(__file__).with_name("check-docs.py"))
DOCS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOCS)


class DocumentationTests(unittest.TestCase):
    def test_local_links_fragments_duplicates_and_missing_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guide.md").write_text("# Setup & usage\n# Setup & usage\n<a id=\"custom\"></a>\n", encoding="utf-8")
            page = root / "README.md"
            page.write_text("[one](guide.md#setup--usage) [two](guide.md#setup--usage-1) [three](guide.md#custom)\n"
                            "[bad](guide.md#absent) [missing](absent.md)\n"
                            "```text\n[example](not-a-file.md)\n```\n[web](https://example.com/path#anything)\n", encoding="utf-8")
            errors = DOCS.check_document(page, root)
            self.assertEqual(2, len(errors))
            self.assertIn("missing heading", errors[0])
            self.assertIn("missing link target", errors[1])

    def test_generated_markers_must_be_unique_and_ordered(self):
        with tempfile.TemporaryDirectory() as directory:
            page = Path(directory) / "README.md"
            pairs = [f"<!-- {name}:start -->\n<!-- {name}:end -->\n" for name in
                     ("benchmark-metrics-badges", "benchmark-target-diagnostics", "benchmark-capacity-details")]
            page.write_text("".join(pairs), encoding="utf-8")
            self.assertEqual([], DOCS.check_markers(page))
            page.write_text("".join(pairs) + pairs[0], encoding="utf-8")
            self.assertEqual(1, len(DOCS.check_markers(page)))


if __name__ == "__main__":
    unittest.main()
