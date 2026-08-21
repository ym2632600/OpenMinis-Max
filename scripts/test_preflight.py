#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from preflight import Check, Report, check_path, exit_code, optional_submodule_paths


class PreflightTests(unittest.TestCase):
    def test_required_path_is_a_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = check_path(Path(directory), "missing.txt")
        self.assertEqual(result.status, "fail")
        self.assertIn("missing", result.detail)

    def test_generated_path_is_only_a_warning(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = check_path(Path(directory), "generated.bin", required=False)
        self.assertEqual(result.status, "warn")
        self.assertIn("generated artifact", result.detail)

    def test_update_none_submodule_is_optional(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            nested = root / "deps" / "ish"
            nested.mkdir(parents=True)
            (nested / ".gitmodules").write_text(
                '[submodule "deps/linux"]\n'
                '    path = deps/linux\n'
                '    url = https://example.invalid/linux.git\n'
                '    update = none\n',
                encoding="utf-8",
            )
            self.assertEqual(optional_submodule_paths(root), {"deps/ish/deps/linux"})

    def test_clean_report_passes(self) -> None:
        report = Report("/repo", "android", [Check("example", "pass", "ok")])
        self.assertEqual(exit_code(report, strict=False), 0)
        self.assertEqual(exit_code(report, strict=True), 0)

    def test_warnings_only_fail_in_strict_mode(self) -> None:
        report = Report("/repo", "android", [Check("example", "warn", "not installed")])
        self.assertEqual(exit_code(report, strict=False), 0)
        self.assertEqual(exit_code(report, strict=True), 2)

    def test_failures_always_fail(self) -> None:
        report = Report("/repo", "android", [Check("example", "fail", "broken")])
        self.assertEqual(exit_code(report, strict=False), 1)
        self.assertEqual(exit_code(report, strict=True), 1)


if __name__ == "__main__":
    unittest.main()
