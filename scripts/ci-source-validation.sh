#!/usr/bin/env bash
# Source-only validation gate. Native Android/iOS builds remain separate jobs because
# they require platform SDKs and are intentionally not hidden behind this lightweight gate.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 -m py_compile scripts/preflight.py scripts/test_preflight.py
./scripts/preflight.py --platform all --scope structure --strict

git diff --check

echo "Source validation passed. Native platform builds are intentionally a separate CI job."
