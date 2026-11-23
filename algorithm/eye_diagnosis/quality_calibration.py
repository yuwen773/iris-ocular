"""质量门阈值的版本化产物。

「模糊阈值」曾经是一个写死在代码里的魔数（先是量纲错误的 12.0，换成正确公式后
又是一个拍脑袋的 100.0）。这类数字必须能回答三个问题：

1. 它是怎么来的？（数据集、样本量、统计口径）
2. 换数据之后怎么重标？（有工具、有命令、有产物格式）
3. 没标定过的时候怎么表示？（必须说「未标定」，不能假装有数）

因此阈值不再写在代码里，而是放在一份版本化产物里：算法读取产物并把版本号随
判读结果一起返回；没有产物或产物未标定时，状态是 UNCALIBRATED——这时质量门
只能以 observe 模式运行（QUALITY_GATE_MODE），并且结论必须标明未标定。
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path

# 产物默认位置：algorithm/configs/calibration/quality_blur_threshold.json
DEFAULT_ARTIFACT_PATH = (
    Path(__file__).resolve().parent.parent / "configs" / "calibration" / "quality_blur_threshold.json"
)

# 未标定时使用的保守阈值：与真实数据无关，只保证「不会因为量纲错误而全放行」。
FALLBACK_THRESHOLD = 100.0

STATE_CALIBRATED = "CALIBRATED"
STATE_UNCALIBRATED = "UNCALIBRATED"


@dataclass(frozen=True)
class QualityCalibration:
    """一份质量门阈值产物。"""

    version: str
    state: str
    threshold: float
    dataset_kind: str
    sample_count: int
    source: str
    note: str = ""

    @property
    def calibrated(self) -> bool:
        return self.state == STATE_CALIBRATED


def load_quality_calibration(path: Path | str | None = None) -> QualityCalibration:
    """读取阈值产物；读不到或内容不合法时退化为「未标定」的保守阈值。"""
    artifact_path = Path(path) if path else DEFAULT_ARTIFACT_PATH
    try:
        raw = json.loads(artifact_path.read_text(encoding="utf-8"))
        threshold = float(raw["threshold"])
        state = str(raw.get("state", STATE_UNCALIBRATED))
        if state not in (STATE_CALIBRATED, STATE_UNCALIBRATED):
            state = STATE_UNCALIBRATED
        return QualityCalibration(
            version=str(raw.get("version", "unknown")),
            state=state,
            threshold=threshold,
            dataset_kind=str(raw.get("datasetKind", "unknown")),
            sample_count=int(raw.get("sampleCount", 0)),
            source=str(raw.get("tool", "")),
            note=str(raw.get("note", "")),
        )
    except (OSError, ValueError, KeyError, TypeError):
        # 产物缺失或损坏：不要静默用一个来路不明的阈值，退回明确的未标定状态。
        return QualityCalibration(
            version="quality-blur-unavailable",
            state=STATE_UNCALIBRATED,
            threshold=FALLBACK_THRESHOLD,
            dataset_kind="unknown",
            sample_count=0,
            source=str(artifact_path),
            note="阈值产物缺失或不可读，已退回未标定的保守阈值。",
        )


def effective_threshold(calibration: QualityCalibration) -> float:
    """环境变量优先（仅用于实验与临时重标），否则用产物里的阈值。"""
    override = os.getenv("QUALITY_BLUR_VARIANCE_MIN")
    if override:
        try:
            return float(override)
        except ValueError:
            pass
    return calibration.threshold
