"""标定工具的行为：不能在证据不足时把状态写成已标定。"""

import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "algorithm" / "tools"))

from calibrate_quality import MIN_CLEAR_SAMPLES, build_artifact  # noqa: E402


def _artifact(dataset_kind, values, clear_names):
    return build_artifact(Path("/tmp/images"), dataset_kind, values, clear_names, "v-test")


def test_synthetic_dataset_is_never_marked_calibrated():
    values = {f"img{i}.png": 200.0 for i in range(50)}
    artifact = _artifact("synthetic", values, list(values))

    assert artifact["state"] == "UNCALIBRATED"
    assert "真实眼底数据" in artifact["note"]


def test_missing_clear_list_is_never_marked_calibrated():
    values = {f"img{i}.png": 200.0 for i in range(50)}
    artifact = _artifact("real", values, [])

    assert artifact["state"] == "UNCALIBRATED"
    assert "清晰样本清单" in artifact["note"]


def test_too_few_clear_samples_is_never_marked_calibrated():
    values = {f"img{i}.png": 200.0 for i in range(10)}
    artifact = _artifact("real", values, list(values))

    assert artifact["state"] == "UNCALIBRATED"
    assert str(MIN_CLEAR_SAMPLES) in artifact["note"]


def test_real_dataset_with_enough_clear_samples_is_calibrated():
    values = {f"img{i}.png": 100.0 + i for i in range(40)}
    artifact = _artifact("real", values, list(values))

    assert artifact["state"] == "CALIBRATED"
    assert artifact["threshold"] > 0
    assert artifact["statistics"]["clear"]["count"] == 40
    assert artifact["version"] == "v-test"
