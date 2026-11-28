"""Negative control for the #17 gate's PostgreSQL health check.

The job-level health check decides whether the isolated database is usable at
all. A check that cannot fail is not a gate, so this test runs the exact command
from `.github/workflows/verify.yml` inside the job's service container and
proves it fails for an unreachable server and a missing database.

A wrong password cannot be the negative control here: the postgres image trusts
loopback connections (`host all all 127.0.0.1/32 trust`), and the health check
runs inside the container. Password authentication is still exercised by the
suite itself, which connects from the runner over TCP with scram.
"""

import os
import re
import subprocess
import unittest
from pathlib import Path

JOB = "  issue17-mysql:"


class Issue17PostgresHealthTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = os.environ["ISSUE17_PG_CONTAINER"]
        workflow = Path(".github/workflows/verify.yml").read_text(encoding="utf-8")
        job = re.split(r"\n  [a-z0-9-]+:\n", workflow.split("\n" + JOB + "\n", 1)[1], maxsplit=1)[0]
        cls.command = re.search(r'--health-cmd="([^"]+)"', job).group(1)

    def probe(self, command):
        return subprocess.run(
            ["docker", "exec", self.container, "sh", "-c", command],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False,
        ).returncode

    def test_ready_database_passes(self):
        self.assertEqual(0, self.probe(self.command))

    def test_unreachable_server_fails(self):
        command = self.command.replace("-h 127.0.0.1", "-h 127.0.0.1 -p 5433")
        self.assertNotEqual(self.command, command)
        self.assertNotEqual(0, self.probe(command))

    def test_missing_database_fails(self):
        command = self.command.replace("-d iris_gate ", "-d iris_gate_missing ")
        self.assertNotEqual(self.command, command)
        self.assertNotEqual(0, self.probe(command))


if __name__ == "__main__":
    unittest.main()
