import io
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from scripts.check_ci_integrity import classify, main, matches_sensitive


class ClassifyTest(unittest.TestCase):
    def test_every_gate_path_family_is_sensitive(self):
        paths = [
            ".github/workflows/verify.yml",
            ".github/CODEOWNERS",
            "scripts/check_surefire_skips.py",
            "scripts/tests/test_check_surefire_skips.py",
            "scripts/lib/docker-test-harness.sh",
            "scripts/verify.sh",
            "scripts/verify-fast.sh",
            ".mvn/maven.config",
        ]
        found = dict(classify(paths))
        self.assertEqual(set(found), set(paths))

    def test_ordinary_changes_are_not_sensitive(self):
        self.assertEqual(classify(["src/main/java/Foo.java", "web/src/App.tsx", "docs/a.md"]), [])

    def test_pom_is_sensitive_only_when_test_execution_changes(self):
        dependency_only = "+        <artifactId>commons-lang3</artifactId>\n"
        self.assertIsNone(matches_sensitive("pom.xml", dependency_only))
        for hunk in (
            "+            <artifactId>maven-surefire-plugin</artifactId>",
            "+                <argLine>-Xmx1g</argLine>",
            "+        <skipTests>true</skipTests>",
            "-            <testFailureIgnore>false</testFailureIgnore>",
        ):
            self.assertIsNotNone(matches_sensitive("pom.xml", hunk), hunk)


class CommandTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "user.name", "ci")
        (self.root / "README.md").write_text("base\n", encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def commit(self, relative, text="x\n"):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        self.git("add", ".")
        self.git("commit", "-q", "-m", relative)

    def run_gate(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main(["--base", self.base, "--repo-root", str(self.root), *extra])
        return code, out.getvalue() + err.getvalue()

    def test_blocks_a_workflow_change_without_acknowledgement(self):
        self.commit(".github/workflows/verify.yml", "on: push\n")
        code, output = self.run_gate()
        self.assertEqual(code, 1)
        self.assertIn(".github/workflows/verify.yml", output)
        self.assertIn("ci-change-approved", output)

    def test_acknowledgement_unblocks_but_still_reports_r3(self):
        self.commit(".github/workflows/verify.yml", "on: push\n")
        code, output = self.run_gate("--acknowledged", "true")
        self.assertEqual(code, 0)
        self.assertIn("R3", output)

    def test_non_sensitive_change_passes(self):
        self.commit("docs/readme.md")
        code, output = self.run_gate()
        self.assertEqual(code, 0)
        self.assertIn("none CI-sensitive", output)

    def test_unknown_base_fails_closed(self):
        code, output = self.run_gate("--base", "0" * 40)
        self.assertEqual(code, 2)
        self.assertIn("git diff", output)


if __name__ == "__main__":
    unittest.main()
