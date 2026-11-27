import io
import tempfile
import textwrap
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from scripts.check_workflow_artifacts import check_file, main

STEP = """\
      - uses: actions/upload-artifact@v4
        with:
          name: {name}
          path: target/reports/
{extra}"""


def workflow(step):
    return textwrap.dedent(
        """\
        name: verify
        on: [pull_request]
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
        """
    ) + step + "\n"


class CheckFileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "verify.yml"

    def write(self, text):
        self.path.write_text(text, encoding="utf-8")
        return check_file(self.path)

    def step(self, name="issue19-surefire", extra=""):
        return STEP.format(name=name, extra=extra)

    def test_upload_without_overwrite_is_flagged(self):
        violations = self.write(workflow(self.step()))
        self.assertEqual(len(violations), 1)
        self.assertIn("overwrite=", violations[0][1])

    def test_overwrite_true_is_safe(self):
        self.assertEqual(self.write(workflow(self.step(extra="          overwrite: true\n"))), [])

    def test_name_with_run_attempt_is_safe(self):
        self.assertEqual(
            self.write(workflow(self.step(name="issue19-${{ github.run_attempt }}"))), []
        )

    def test_overwrite_false_is_flagged(self):
        violations = self.write(workflow(self.step(extra="          overwrite: false\n")))
        self.assertEqual(len(violations), 1)

    def test_step_level_name_mentioning_run_attempt_does_not_count(self):
        step = (
            "      - name: run_attempt note\n"
            "        uses: actions/upload-artifact@v4\n"
            "        with:\n"
            "          name: issue19-surefire\n"
            "          path: target/\n"
        )
        violations = self.write(workflow(step))
        self.assertEqual(len(violations), 1)

    def test_run_block_text_cannot_make_a_step_safe(self):
        step = (
            "      - uses: actions/upload-artifact@v4\n"
            "        with:\n"
            "          name: issue19-surefire\n"
            "          path: target/\n"
            "        run: |\n"
            "          echo overwrite: true\n"
        )
        violations = self.write(workflow(step))
        self.assertEqual(len(violations), 1)

    def test_non_upload_steps_are_ignored(self):
        self.assertEqual(self.write(workflow("")), [])

    def test_reports_the_upload_line(self):
        violations = self.write(workflow(self.step()))
        # the checkout step is line 7, so the upload `uses:` is line 8 (1-based).
        self.assertEqual(violations[0][0], 8)

    def test_multiple_jobs_are_scanned(self):
        text = (
            "jobs:\n"
            "  a:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            + self.step(extra="          overwrite: true\n")
            + "  b:\n"
            "    runs-on: ubuntu-latest\n"
            "    steps:\n"
            + self.step()
        )
        violations = self.write(text)
        self.assertEqual(len(violations), 1)


class MainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / ".github" / "workflows").mkdir(parents=True)

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main(["--root", str(self.root), *extra])
        return code, out.getvalue() + err.getvalue()

    def test_violation_exits_one(self):
        (self.root / ".github" / "workflows" / "verify.yml").write_text(
            workflow(STEP.format(name="x", extra="")), encoding="utf-8"
        )
        code, output = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("verify.yml", output)

    def test_clean_exits_zero(self):
        (self.root / ".github" / "workflows" / "verify.yml").write_text(
            workflow(STEP.format(name="x", extra="          overwrite: true\n")), encoding="utf-8"
        )
        code, output = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("re-run-safe", output)

    def test_missing_workflows_exits_two(self):
        code, _ = self.run_main()
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
