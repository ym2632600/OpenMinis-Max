#!/usr/bin/env python3
"""Validate the local prerequisites for building Minis.

This check is intentionally read-only. It never downloads dependencies, edits
configuration files, or changes the working tree. Use it before running the
platform-specific build scripts described in BUILDING.md.
"""

from __future__ import annotations

import argparse
import configparser
import json
import os
import platform
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class Check:
    name: str
    status: str  # pass, warn, fail
    detail: str
    remediation: str | None = None


@dataclass(frozen=True)
class Report:
    repo: str
    platform: str
    checks: list[Check]

    @property
    def failures(self) -> list[Check]:
        return [check for check in self.checks if check.status == "fail"]

    @property
    def warnings(self) -> list[Check]:
        return [check for check in self.checks if check.status == "warn"]


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def check_path(root: Path, relative: str, *, required: bool = True) -> Check:
    path = root / relative
    if path.exists():
        kind = "directory" if path.is_dir() else "file"
        return Check("path:" + relative, "pass", f"{kind} exists")

    if required:
        return Check(
            "path:" + relative,
            "fail",
            "required path is missing",
            f"restore or generate {relative} before building",
        )
    return Check(
        "path:" + relative,
        "warn",
        "generated artifact is not present",
        f"run the build step that produces {relative}",
    )


def check_command(name: str, *, required: bool, detail: str) -> Check:
    resolved = shutil.which(name)
    if resolved:
        return Check(f"command:{name}", "pass", f"{resolved} ({detail})")

    status = "fail" if required else "warn"
    remediation = f"install or expose {name} on PATH"
    return Check(f"command:{name}", status, "command not found", remediation)


def check_environment_path(name: str, *, required: bool, detail: str) -> Check:
    value = os.environ.get(name)
    if value and Path(value).expanduser().exists():
        return Check(f"environment:{name}", "pass", f"{Path(value).expanduser()} ({detail})")

    status = "fail" if required else "warn"
    return Check(
        f"environment:{name}",
        status,
        "environment variable is unset or points to a missing path",
        f"set {name} to the installed {detail}",
    )


def optional_submodule_paths(root: Path) -> set[str]:
    """Return submodule paths explicitly marked with update=none."""
    optional: set[str] = set()
    for gitmodules in root.rglob(".gitmodules"):
        if ".git" in gitmodules.parts:
            continue
        parser = configparser.ConfigParser()
        try:
            with gitmodules.open(encoding="utf-8") as stream:
                parser.read_file(stream)
        except (OSError, configparser.Error):
            continue
        base = gitmodules.parent
        for section in parser.sections():
            if not section.startswith("submodule "):
                continue
            if parser.get(section, "update", fallback="").strip() == "none":
                path = parser.get(section, "path", fallback="").strip()
                if path:
                    optional.add(str((base / path).relative_to(root)))
    return optional


def submodule_checks(root: Path) -> list[Check]:
    optional_paths = optional_submodule_paths(root)
    try:
        completed = subprocess.run(
            ["git", "-C", str(root), "submodule", "status", "--recursive"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        return [
            Check(
                "git:submodules",
                "fail",
                f"could not inspect submodules: {exc}",
                "run git submodule update --init --recursive",
            )
        ]

    checks: list[Check] = []
    for raw_line in completed.stdout.splitlines():
        line = raw_line.rstrip()
        if not line:
            continue
        state = line[0]
        path = line[1:].strip().split(" ", 1)[-1]
        if state == "-":
            if path in optional_paths:
                checks.append(
                    Check(
                        f"submodule:{path}",
                        "warn",
                        "submodule is intentionally disabled with update=none",
                        "initialize it only when building the iSH component that requires the Linux source tree",
                    )
                )
            else:
                checks.append(
                    Check(
                        f"submodule:{path}",
                        "fail",
                        "submodule is not initialized",
                        "run git submodule update --init --recursive",
                    )
                )
        elif state in {"+", "U"}:
            checks.append(
                Check(
                    f"submodule:{path}",
                    "warn",
                    "submodule checkout differs from the recorded commit",
                    "checkout the commit recorded by the superproject unless intentionally testing a fork",
                )
            )
        else:
            checks.append(Check(f"submodule:{path}", "pass", "initialized at recorded commit"))

    if not checks:
        checks.append(
            Check(
                "git:submodules",
                "fail",
                "repository has no readable submodule status",
                "run git submodule update --init --recursive",
            )
        )
    return checks


def sdk_roots() -> list[Path]:
    roots: list[Path] = []
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            candidate = Path(value).expanduser()
            if candidate.exists() and candidate not in roots:
                roots.append(candidate)
    return roots


def check_android_sdk_command(
    name: str,
    relative_path: str,
    *,
    required: bool,
    detail: str,
) -> Check:
    resolved = shutil.which(name)
    if resolved:
        return Check(f"command:{name}", "pass", f"{resolved} ({detail})")

    for root in sdk_roots():
        candidate = root / relative_path
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return Check(f"command:{name}", "pass", f"{candidate} ({detail}, Android SDK)")

    status = "fail" if required else "warn"
    return Check(
        f"command:{name}",
        status,
        "command not found on PATH or in the configured Android SDK",
        f"install or expose {name} on PATH, or install it under the Android SDK",
    )


def check_android_ndk() -> Check:
    for name in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        value = os.environ.get(name)
        if value and (candidate := Path(value).expanduser()).is_dir():
            return Check(f"environment:{name}", "pass", f"{candidate} (Android NDK)")

    candidates = [candidate for root in sdk_roots() for candidate in (root / "ndk").glob("*") if candidate.is_dir()]
    if candidates:
        selected = sorted(candidates, key=lambda candidate: candidate.name, reverse=True)[0]
        return Check("environment:ANDROID_NDK", "pass", f"{selected} (side-by-side Android NDK)")

    return Check(
        "environment:ANDROID_NDK",
        "fail",
        "Android NDK was not found through ANDROID_NDK_HOME, ANDROID_NDK_ROOT, or the SDK side-by-side directory",
        "install NDK r28+ and set ANDROID_NDK_HOME, or configure ANDROID_SDK_ROOT/ANDROID_HOME",
    )


def common_checks(root: Path) -> list[Check]:
    checks = [
        check_command("git", required=True, detail="repository and submodule inspection"),
        check_command("bash", required=True, detail="build scripts"),
        check_command("curl", required=True, detail="dependency downloads"),
        check_command("tar", required=True, detail="sandbox archive preparation"),
        check_path(root, "BUILDING.md"),
        check_path(root, ".gitmodules"),
        check_path(root, "src/android/gradlew"),
        check_path(root, "src/android/app/build.gradle.kts"),
        check_path(root, "src/ios/Minis.xcodeproj"),
        check_path(root, "src/ios/Configs/ProviderCustomization.xcconfig.example"),
        check_path(root, "src/android/app/provider-customization.properties.example"),
    ]
    checks.extend(submodule_checks(root))
    return checks


def android_checks(root: Path) -> list[Check]:
    checks = [
        check_command("java", required=True, detail="Gradle runtime"),
        check_command("javac", required=True, detail="Gradle Java compiler and unit tests"),
        check_android_sdk_command(
            "cmake",
            "cmake/3.22.1/bin/cmake",
            required=True,
            detail="JNI native build",
        ),
        check_android_sdk_command(
            "adb",
            "platform-tools/adb",
            required=False,
            detail="optional device/instrumented tests",
        ),
        check_environment_path("ANDROID_SDK_ROOT", required=False, detail="Android SDK"),
        check_environment_path("ANDROID_HOME", required=False, detail="Android SDK"),
        check_android_ndk(),
        check_path(root, "src/android/app/src/main/assets/proot-aarch64", required=False),
        check_path(root, "src/android/app/src/main/assets/alpine-minirootfs.tar.gz", required=False),
        check_path(
            root,
            "src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so",
            required=False,
        ),
        check_path(
            root,
            "src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader32.so",
            required=False,
        ),
    ]
    return checks


def ios_checks(root: Path) -> list[Check]:
    checks = [
        check_command("xcodebuild", required=True, detail="iOS app build"),
        check_command("python3", required=True, detail="Meson helper scripts"),
        check_command("meson", required=True, detail="iSH native build"),
        check_command("ninja", required=True, detail="iSH native build"),
        check_command("pkg-config", required=True, detail="native dependency discovery"),
        check_command("llvm", required=True, detail="guest VDSO build"),
        check_path(root, "deps/lame-build/lib/libmp3lame.a", required=False),
        check_path(root, "deps/frameworks", required=False),
        check_path(root, "deps/libs", required=False),
        check_path(root, "deps/resources", required=False),
    ]
    return checks


def build_report(root: Path, target: str) -> Report:
    checks = common_checks(root)
    if target in {"android", "all"}:
        checks.extend(android_checks(root))
    if target in {"ios", "all"}:
        checks.extend(ios_checks(root))
    return Report(str(root), target, checks)


def print_human(report: Report) -> None:
    print(f"Minis preflight: {report.repo}")
    print(f"Target: {report.platform} | Host: {platform.system()} {platform.machine()}")
    for check in report.checks:
        prefix = {"pass": "PASS", "warn": "WARN", "fail": "FAIL"}[check.status]
        print(f"[{prefix}] {check.name}: {check.detail}")
        if check.remediation:
            print(f"       -> {check.remediation}")
    print(f"Summary: {len(report.failures)} failure(s), {len(report.warnings)} warning(s)")


def exit_code(report: Report, strict: bool) -> int:
    if report.failures:
        return 1
    if strict and report.warnings:
        return 2
    return 0


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--platform",
        choices=("android", "ios", "all"),
        default="all",
        help="build target to validate (default: all)",
    )
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="return exit code 2 when warnings remain, useful in CI",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    report = build_report(repo_root(), args.platform)
    if args.json:
        payload = asdict(report)
        payload["summary"] = {
            "failures": len(report.failures),
            "warnings": len(report.warnings),
            "passed": len(report.checks) - len(report.failures) - len(report.warnings),
        }
        print(json.dumps(payload, indent=2))
    else:
        print_human(report)
    return exit_code(report, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())
