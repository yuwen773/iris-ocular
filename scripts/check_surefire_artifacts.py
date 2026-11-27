#!/usr/bin/env python3
"""Move Surefire reports between jobs without trusting the sender.

`issue24-evaluation` used to re-run `PatientDraftTest` (41 cases) and
`PatientExplanationApiTest` (37 cases) only to obtain their Surefire XML.
Those suites already run in `issue19-mysql` / `issue21-mysql`; re-running them
duplicated 78 executions.  This script replaces that duplication with an
explicit artifact contract:

  export   (producer job) copies one report, records the commit, run, attempt,
           suite, counts and the XML SHA-256, and refuses to export a report
           that skipped, failed or lost cases.
  assemble (consumer job) reads every provenance file, rejects anything whose
           commit / run / attempt does not match this run, re-hashes the XML,
           re-counts the cases, refuses duplicate suites or stray reports, and
           only then copies them into the report directory.

The point is that a stale or hand-crafted report cannot satisfy the consumer:
the provenance is bound to ``GITHUB_SHA`` / ``GITHUB_RUN_ID`` (which a PR cannot
choose) and the XML hash is bound to the exact bytes the producer saw.

Usage:
    python3 scripts/check_surefire_artifacts.py export \
        --source target/surefire-reports --dest target/surefire-artifact \
        --suite com.example.FooTest --job issue19-mysql
    python3 scripts/check_surefire_artifacts.py assemble \
        --source artifacts/issue19 --source artifacts/issue21 \
        --dest target/surefire-reports

Exit codes: 0 = ok, 1 = contract violation, 2 = usage.
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from xml.etree import ElementTree

PROVENANCE = "provenance.json"
COMBINED = "artifact-provenance.json"


def counts(path):
    root = ElementTree.parse(path).getroot()
    if root.tag != "testsuite":
        raise ValueError(f"{path.name}: root is <{root.tag}>, expected <testsuite>")
    cases = root.findall("testcase")

    def number(key):
        try:
            return int(root.get(key, "0"))
        except ValueError as exc:
            raise ValueError(f"{path.name}: non-numeric {key}={root.get(key)!r}") from exc

    child_skipped = sum(1 for case in cases if case.find("skipped") is not None)
    return {
        "suite": root.get("name") or path.stem.removeprefix("TEST-"),
        "tests": number("tests"),
        "cases": len(cases),
        "skipped": max(number("skipped"), child_skipped),
        "failures": number("failures"),
        "errors": number("errors"),
    }


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require_complete(name, c):
    if c["tests"] != c["cases"]:
        raise ValueError(f"{name}: tests={c['tests']} but {c['cases']} testcase elements")
    if c["tests"] < 1:
        raise ValueError(f"{name}: report contains no cases")
    if c["skipped"] or c["failures"] or c["errors"]:
        raise ValueError(
            f"{name}: refusing to publish a report with skipped={c['skipped']} "
            f"failures={c['failures']} errors={c['errors']}"
        )


def export(source, dest, suite, job, sha, run_id, run_attempt):
    source, dest = Path(source), Path(dest)
    report = source / f"TEST-{suite}.xml"
    if not report.is_file():
        raise ValueError(f"{report.name}: no report in {source}")
    c = counts(report)
    if c["suite"] != suite:
        raise ValueError(f"{report.name}: suite name is {c['suite']!r}, expected {suite!r}")
    require_complete(report.name, c)
    if not (sha and run_id and run_attempt):
        raise ValueError("export needs commit SHA, run id and run attempt")

    dest.mkdir(parents=True, exist_ok=True)
    copy = dest / report.name
    shutil.copyfile(report, copy)
    provenance = {
        "schema": 1,
        "suite": suite,
        "job": job,
        "commit": sha,
        "runId": str(run_id),
        "runAttempt": str(run_attempt),
        "tests": c["tests"],
        "skipped": c["skipped"],
        "failures": c["failures"],
        "errors": c["errors"],
        "xmlSha256": digest(copy),
        "xmlFile": copy.name,
    }
    (dest / PROVENANCE).write_text(
        json.dumps(provenance, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n"
    )
    print(f"exported {suite}: {c['tests']} cases, sha256={provenance['xmlSha256'][:12]}")
    return provenance


def assemble(sources, dest, expected_sha, expected_run, expected_attempt):
    dest = Path(dest)
    if dest.exists():
        stale = sorted(p.name for p in dest.glob("TEST-*.xml"))
        if stale:
            raise ValueError(f"{dest}: already contains reports {stale}; refusing to mix runs")
    dest.mkdir(parents=True, exist_ok=True)

    seen, assembled = {}, []
    for source in sources:
        source = Path(source)
        meta_path = source / PROVENANCE
        if not meta_path.is_file():
            raise ValueError(f"{source}: missing {PROVENANCE}")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        for key in ("suite", "commit", "runId", "runAttempt", "xmlFile", "xmlSha256", "tests"):
            if key not in meta:
                raise ValueError(f"{meta_path}: missing {key!r}")

        expected = {"commit": expected_sha, "runId": str(expected_run), "runAttempt": str(expected_attempt)}
        for key, value in expected.items():
            if str(meta[key]) != str(value):
                raise ValueError(
                    f"{source}: {key}={meta[key]!r} does not match this run ({value!r}) — "
                    "refusing to reuse reports from another commit or run"
                )

        report = source / meta["xmlFile"]
        if not report.is_file():
            raise ValueError(f"{source}: {meta['xmlFile']} missing")
        if digest(report) != meta["xmlSha256"]:
            raise ValueError(f"{report}: SHA-256 does not match provenance (tampered or truncated)")
        c = counts(report)
        require_complete(report.name, c)
        if c["suite"] != meta["suite"]:
            raise ValueError(f"{report}: suite name {c['suite']!r} != provenance {meta['suite']!r}")
        if c["tests"] != int(meta["tests"]):
            raise ValueError(f"{report}: {c['tests']} cases != provenance {meta['tests']}")

        extra = sorted(p.name for p in source.glob("TEST-*.xml") if p.name != report.name)
        if extra:
            raise ValueError(f"{source}: unexpected reports {extra}; one suite per artifact")

        if meta["suite"] in seen:
            raise ValueError(f"{meta['suite']}: supplied by both {seen[meta['suite']]} and {source}")
        seen[meta["suite"]] = str(source)

        target = dest / report.name
        shutil.copyfile(report, target)
        assembled.append(meta)
        print(f"assembled {meta['suite']}: {c['tests']} cases from {meta['job']}")

    if not assembled:
        raise ValueError("no artifacts assembled")
    (dest / COMBINED).write_text(
        json.dumps({"schema": 1, "commit": expected_sha, "runId": str(expected_run),
                    "runAttempt": str(expected_attempt), "reports": assembled},
                   indent=2, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n",
    )
    return assembled


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    e = sub.add_parser("export")
    e.add_argument("--source", required=True, type=Path)
    e.add_argument("--dest", required=True, type=Path)
    e.add_argument("--suite", required=True)
    e.add_argument("--job", required=True)

    a = sub.add_parser("assemble")
    a.add_argument("--source", required=True, action="append", type=Path)
    a.add_argument("--dest", required=True, type=Path)

    args = parser.parse_args(argv)
    try:
        if args.command == "export":
            export(args.source, args.dest, args.suite, args.job,
                   os.environ.get("GITHUB_SHA"), os.environ.get("GITHUB_RUN_ID"),
                   os.environ.get("GITHUB_RUN_ATTEMPT"))
        else:
            assemble(args.source, args.dest, os.environ.get("GITHUB_SHA"),
                     os.environ.get("GITHUB_RUN_ID"), os.environ.get("GITHUB_RUN_ATTEMPT"))
    except (ValueError, ElementTree.ParseError) as exc:
        print(f"surefire artifact contract failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
