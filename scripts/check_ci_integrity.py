#!/usr/bin/env python3
"""Fail a pull request that touches CI-sensitive files without owner acknowledgement.

Why this exists
---------------
For `pull_request`, GitHub runs the workflow **from the pull request**, so no job
inside that workflow can be its own root of trust: the PR can edit the job, its
steps, or the required check name.  Two controls actually hold:

1. Branch protection with `require_code_owner_reviews` reads CODEOWNERS from the
   **base** branch.  A PR that edits `.github/CODEOWNERS` cannot remove the review
   requirement from itself.  Listing the gate files there turns "silently rewrite
   the gate" into "the owner must consciously merge with an admin override",
   which the GitHub audit log records.
2. This script.  It is the cheap, visible line: it prints every sensitive path
   and requires the `ci-change-approved` label, which needs triage/write access
   (a fork author cannot set it).  It is deliberately **not** sufficient alone —
   a PR can delete this step and `docs-contract` would still report success by
   name.  It exists so that the change is never silent and so that an honest
   author is stopped before merging.

What counts as sensitive is data, not policy: see SENSITIVE_PREFIXES /
SENSITIVE_FILES.  Adding a file to that list is itself a change to this file,
so it goes through the same gate.

Usage:
    python3 scripts/check_ci_integrity.py --base <sha> [--head HEAD]
        [--acknowledged true|false] [--repo-root .]

Exit codes: 0 = no sensitive change or acknowledged, 1 = blocked, 2 = usage.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

# (path prefix, human reason). Order matters only for the message.
SENSITIVE_PREFIXES = (
    (".github/", "workflow, CODEOWNERS or action definition"),
    ("scripts/check_", "gate implementation"),
    ("scripts/tests/", "gate self-tests"),
    ("scripts/lib/", "shared test harness (containers, volumes, teardown)"),
    ("scripts/verify", "local verification entry point"),
    (".mvn/", "Maven configuration"),
)

SENSITIVE_FILES = ("pom.xml",)

# A pom change is only gated when it can change *how tests run*.
POM_EXECUTION_PATTERN = re.compile(
    r"(maven-surefire-plugin|maven-failsafe-plugin|argLine|skipTests|"
    r"<testFailureIgnore>|<test>|surefire\.)",
    re.IGNORECASE,
)


def matches_sensitive(path, pom_diff=""):
    """Return a reason string when ``path`` is CI-sensitive, else None."""
    for prefix, reason in SENSITIVE_PREFIXES:
        if path.startswith(prefix):
            return reason
    if path in SENSITIVE_FILES:
        return "test execution configuration" if POM_EXECUTION_PATTERN.search(pom_diff) else None
    return None


def classify(changed, pom_diff=""):
    """Return [(path, reason)] for every sensitive path in ``changed``."""
    found = []
    for path in changed:
        reason = matches_sensitive(path, pom_diff)
        if reason:
            found.append((path, reason))
    return found


def git(root, *args):
    result = subprocess.run(
        ["git", *args], cwd=str(root), capture_output=True, text=True, encoding="utf-8"
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def changed_files(root, base, head):
    out = git(root, "diff", "--no-renames", "--name-only", base, head)
    return [line.strip() for line in out.splitlines() if line.strip()]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="pull request base commit")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--acknowledged", default="false", help="'true' when the PR carries ci-change-approved")
    parser.add_argument("--repo-root", default=".", type=Path)
    args = parser.parse_args(argv)

    root = args.repo_root.resolve()
    try:
        changed = changed_files(root, args.base, args.head)
        pom_diff = ""
        if "pom.xml" in changed:
            pom_diff = git(root, "diff", "--no-renames", args.base, args.head, "--", "pom.xml")
    except RuntimeError as exc:
        print(f"ci integrity gate: {exc}", file=sys.stderr)
        return 2

    sensitive = classify(changed, pom_diff)
    if not sensitive:
        print(f"ci integrity gate passed: {len(changed)} changed file(s), none CI-sensitive")
        return 0

    print("CI-sensitive paths changed by this pull request:", file=sys.stderr)
    for path, reason in sensitive:
        print(f"  {path} — {reason}", file=sys.stderr)

    if args.acknowledged.strip().lower() == "true":
        print(
            "ci integrity gate: acknowledged with label 'ci-change-approved'. "
            "Risk class R3 — the owner still has to review this diff."
        )
        return 0

    print(
        "\nThis pull request changes the gate itself. Add the 'ci-change-approved' label "
        "only after the owner has read the diff, then re-run this job.\n"
        "The label needs triage or write access, so it is not self-service for contributors.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
