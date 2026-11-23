"""质量门阈值产物的单元测试：版本、出处与「未标定」状态必须诚实。"""

import json

from eye_diagnosis.quality_calibration import (
    DEFAULT_ARTIFACT_PATH,
    STATE_CALIBRATED,
    STATE_UNCALIBRATED,
    load_quality_calibration,
)


def test_shipped_artifact_is_explicitly_uncalibrated():
    calibration = load_quality_calibration()
    assert calibration.state == STATE_UNCALIBRATED
    assert calibration.dataset_kind == "synthetic-fixtures"
    assert calibration.sample_count > 0
    assert calibration.version
    assert calibration.threshold > 0


def test_artifact_records_its_provenance():
    raw = json.loads(DEFAULT_ARTIFACT_PATH.read_text(encoding="utf-8"))
    assert raw["tool"].startswith("algorithm/tools/")
    assert raw["note"], "产物必须写清它是怎么来的、还差什么"


def test_missing_artifact_falls_back_to_uncalibrated(tmp_path):
    calibration = load_quality_calibration(tmp_path / "nope.json")
    assert calibration.state == STATE_UNCALIBRATED
    assert calibration.sample_count == 0


def test_broken_artifact_falls_back_to_uncalibrated(tmp_path):
    broken = tmp_path / "broken.json"
    broken.write_text("{not json", encoding="utf-8")
    calibration = load_quality_calibration(broken)
    assert calibration.state == STATE_UNCALIBRATED


def test_unknown_state_is_treated_as_uncalibrated(tmp_path):
    artifact = tmp_path / "weird.json"
    artifact.write_text(json.dumps({"version": "v", "state": "CALIBRATED-ish", "threshold": 5}),
                        encoding="utf-8")
    calibration = load_quality_calibration(artifact)
    assert calibration.state == STATE_UNCALIBRATED
    assert calibration.calibrated is False
