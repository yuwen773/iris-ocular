#!/usr/bin/env python3
"""Fail when a workflow uploads an artifact in a way that breaks a re-run.

Why this exists
---------------
`actions/upload-artifact@v4` makes every artifact name immutable inside a
workflow run: a re-run attempt that reaches the same `name:` dies with
`409 Conflict: an artifact with this name already exists on the workflow run`
(actions/upload-artifact#478).  Re-running is the documented recovery for a
flaky or rate-limited job, so a workflow that is ever re-run must either
overwrite its artifact or namespace it per attempt.

A step is re-run-safe when its `with:` block does one of:

  * sets `overwrite: true`, or
  * puts `${{ github.run_attempt }}` in `name:`.

Scope is deliberately narrow: only the `with:` map of `actions/upload-artifact`
steps is read, so text inside a `run: |` body can never make a step look safe.

Usage:
    python3 scripts/check_workflow_artifacts.py [paths ...]
    python3 scripts/check_workflow_artifacts.py --root .   # default: .github/workflows/*.yml

Exit codes: 0 = every upload is re-run-safe, 1 = at least one is not, 2 = usage/IO.
"""

import argparse
import re
import sys
from pathlib import Path

_UPLOAD = re.compile(r"\buses:\s*actions/upload-artifact\b")
_WITH = re.compile(r"^(\s*)with:\s*$")
_TRUE = {"true", "yes", "on", "1"}


def _indent(line):
    return len(line) - len(line.lstrip(" "))


def _upload_steps(lines):
    """Yield (uses_line_no, dash_indent, block_lines) for each upload-artifact step."""
    index, total = 0, len(lines)
    while index < total:
        head = re.match(r"^(\s*)-\s+", lines[index])
        if not head:
            index += 1
            continue
        dash_indent = len(head.group(1))
        block = [lines[index]]
        uses_line = index + 1 if _UPLOAD.search(lines[index]) else None
        cursor = index + 1
        while cursor < total:
            nxt = lines[cursor]
            if nxt.strip() == "":
                block.append(nxt)
                cursor += 1
                continue
            if _indent(nxt) <= dash_indent:
                break
            block.append(nxt)
            if uses_line is None and _UPLOAD.search(nxt):
                uses_line = cursor + 1
            cursor += 1
        if uses_line is not None:
            yield uses_line, block
        index = cursor if cursor > index else index + 1


def _with_children(block):
    """Return the lines nested under the step's `with:` key, or None if absent."""
    for position, line in enumerate(block):
        match = _WITH.match(line)
        if not match:
            continue
        with_indent = len(match.group(1))
        children = []
        for child in block[position + 1:]:
            if child.strip() == "":
                continue
            if _indent(child) <= with_indent:
                break
            children.append(child)
        return children
    return None


def _value(children, key):
    pattern = re.compile(r"^\s*" + re.escape(key) + r":\s*(.*)$")
    for child in children:
        match = pattern.match(child)
        if match:
            return match.group(1).strip().strip("\"'")
    return None


def rerun_safe(block):
    """Return (is_safe, detail) for one upload step's block of lines."""
    children = _with_children(block)
    if children is None:
        return False, "no `with:` block"
    overwrite = (_value(children, "overwrite") or "").lower()
    name = _value(children, "name") or ""
    if overwrite in _TRUE:
        return True, "overwrite: true"
    if "run_attempt" in name:
        return True, "name uses github.run_attempt"
    return False, f"overwrite={overwrite!r}, name={name!r}"


def check_file(path):
    """Return [(line_no, detail)] for every re-run-unsafe upload in one file."""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    violations = []
    for line_no, block in _upload_steps(lines):
        safe, detail = rerun_safe(block)
        if not safe:
            violations.append((line_no, detail))
    return violations


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--root", default=".", type=Path)
    args = parser.parse_args(argv)

    if args.paths:
        files = sorted(args.paths)
    else:
        directory = args.root / ".github" / "workflows"
        files = sorted([*directory.glob("*.yml"), *directory.glob("*.yaml")])

    if not files:
        print(f"workflow artifact gate: no workflow files under {args.root}", file=sys.stderr)
        return 2

    found = []
    for path in files:
        try:
            for line_no, detail in check_file(path):
                found.append((path, line_no, detail))
        except OSError as exc:
            print(f"workflow artifact gate: cannot read {path}: {exc}", file=sys.stderr)
            return 2

    if found:
        print(
            "Re-run-unsafe artifact uploads (upload-artifact names are immutable per run):",
            file=sys.stderr,
        )
        for path, line_no, detail in found:
            print(f"  {path}:{line_no} — {detail}", file=sys.stderr)
        print(
            "\nFix: add `overwrite: true` to the step's `with:`, or put "
            "`${{ github.run_attempt }}` in `with: name:`.\n"
            "See docs/testing/ci-trust-model.md (artifacts & re-run).",
            file=sys.stderr,
        )
        return 1

    print(f"workflow artifact gate passed: {len(files)} file(s), all uploads re-run-safe")
    return 0


if __name__ == "__main__":
    sys.exit(main())
