import json
import tempfile
import unittest
from pathlib import Path

from scripts.check_surefire_artifacts import assemble, export

SHA = "a" * 40
RUN = "4242"
ATTEMPT = "1"


def surefire(suite, tests, skipped=0, failures=0, errors=0):
    cases = []
    for index in range(tests):
        outcome = ""
        if index < skipped:
            outcome = "<skipped/>"
        elif index < skipped + failures:
            outcome = "<failure/>"
        elif index < skipped + failures + errors:
            outcome = "<error/>"
        cases.append(f'<testcase name="case{index}" classname="{suite}">{outcome}</testcase>')
    return (
        f'<testsuite name="{suite}" tests="{tests}" skipped="{skipped}" '
        f'failures="{failures}" errors="{errors}">' + "".join(cases) + "</testsuite>"
    )


class Workdir(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def export(self, suite, job, tests=3, **kwargs):
        self.write(f"reports/TEST-{suite}.xml", surefire(suite, tests, **kwargs))
        return export(self.root / "reports", self.root / job, suite, job, SHA, RUN, ATTEMPT)


class ExportTest(Workdir):
    def test_export_records_provenance_bound_to_this_run(self):
        meta = self.export("com.example.FooTest", "job-a")
        self.assertEqual(meta["commit"], SHA)
        self.assertEqual(meta["runId"], RUN)
        self.assertEqual(meta["tests"], 3)
        self.assertTrue((self.root / "job-a" / "TEST-com.example.FooTest.xml").is_file())
        stored = json.loads((self.root / "job-a" / "provenance.json").read_text(encoding="utf-8"))
        self.assertEqual(stored["xmlSha256"], meta["xmlSha256"])

    def test_export_refuses_skipped_or_failed_reports(self):
        self.write("reports/TEST-com.example.FooTest.xml", surefire("com.example.FooTest", 3, skipped=1))
        with self.assertRaises(ValueError):
            export(self.root / "reports", self.root / "job-a", "com.example.FooTest", "job-a", SHA, RUN, ATTEMPT)
        self.write("reports/TEST-com.example.FooTest.xml", surefire("com.example.FooTest", 3, failures=1))
        with self.assertRaises(ValueError):
            export(self.root / "reports", self.root / "job-a", "com.example.FooTest", "job-a", SHA, RUN, ATTEMPT)

    def test_export_refuses_a_missing_report(self):
        with self.assertRaises(ValueError):
            export(self.root / "reports", self.root / "job-a", "com.example.FooTest", "job-a", SHA, RUN, ATTEMPT)


class AssembleTest(Workdir):
    def setUp(self):
        super().setUp()
        self.export("com.example.FooTest", "job-a", tests=4)
        self.export("com.example.BarTest", "job-b", tests=2)

    def assemble(self, **kwargs):
        options = dict(expected_sha=SHA, expected_run=RUN, expected_attempt=ATTEMPT)
        options.update(kwargs)
        return assemble([self.root / "job-a", self.root / "job-b"], self.root / "merged", **options)

    def test_assembles_reports_and_combined_provenance(self):
        self.assemble()
        merged = self.root / "merged"
        self.assertTrue((merged / "TEST-com.example.FooTest.xml").is_file())
        self.assertTrue((merged / "TEST-com.example.BarTest.xml").is_file())
        combined = json.loads((merged / "artifact-provenance.json").read_text(encoding="utf-8"))
        self.assertEqual({r["suite"] for r in combined["reports"]},
                         {"com.example.FooTest", "com.example.BarTest"})

    def test_rejects_reports_from_another_commit(self):
        with self.assertRaises(ValueError) as caught:
            self.assemble(expected_sha="b" * 40)
        self.assertIn("commit", str(caught.exception))

    def test_rejects_reports_from_another_run(self):
        with self.assertRaises(ValueError):
            self.assemble(expected_run="9999")

    def test_rejects_tampered_xml(self):
        path = self.root / "job-a" / "TEST-com.example.FooTest.xml"
        path.write_text(surefire("com.example.FooTest", 4).replace("case0", "case0x"), encoding="utf-8")
        with self.assertRaises(ValueError) as caught:
            self.assemble()
        self.assertIn("SHA-256", str(caught.exception))

    def test_rejects_stray_reports_in_one_artifact(self):
        self.write("job-a/TEST-com.example.ExtraTest.xml", surefire("com.example.ExtraTest", 1))
        with self.assertRaises(ValueError) as caught:
            self.assemble()
        self.assertIn("unexpected reports", str(caught.exception))

    def test_rejects_the_same_suite_twice(self):
        self.write("job-b/TEST-com.example.FooTest.xml", surefire("com.example.FooTest", 4))
        source = self.root / "job-b" / "provenance.json"
        meta = json.loads(source.read_text(encoding="utf-8"))
        meta["suite"] = "com.example.FooTest"
        meta["xmlFile"] = "TEST-com.example.FooTest.xml"
        from scripts.check_surefire_artifacts import digest
        meta["xmlSha256"] = digest(self.root / "job-b" / "TEST-com.example.FooTest.xml")
        source.write_text(json.dumps(meta), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.assemble()

    def test_refuses_to_mix_with_existing_reports(self):
        self.write("merged/TEST-com.example.Old.xml", surefire("com.example.Old", 1))
        with self.assertRaises(ValueError) as caught:
            self.assemble()
        self.assertIn("already contains", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
