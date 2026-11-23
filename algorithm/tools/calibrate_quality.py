"""重标质量门（模糊）阈值，产出版本化阈值产物。

用法::

    # 真实数据集：必须给出清晰样本清单，且样本量达标才会写 CALIBRATED
    python algorithm/tools/calibrate_quality.py \
        --images /path/to/real-dataset \
        --clear-list /path/to/clear-images.txt \
        --dataset-kind real \
        --output algorithm/configs/calibration/quality_blur_threshold.json

    # 合成夹具：只做冒烟验证，产物状态仍然是 UNCALIBRATED
    python algorithm/tools/calibrate_quality.py \
        --images algorithm/eye_diagnosis_p1/tests/fixtures/images \
        --dataset-kind synthetic

阈值口径：取「清晰样本」拉普拉斯响应方差的 5 分位数——即 95% 的清晰样本高于
该阈值。离焦样本不被用来定阈值，只在报告里给出分布，便于复核。

**不做的事**：不会因为数据集看起来「够大」就把状态写成 CALIBRATED。只有
真实数据集 + 明确清单 + 足够样本量才会标定；否则产物写 UNCALIBRATED，并写清
原因。这条规则是刻意的：一个没有证据的 CALIBRATED 会让下游把 softmax 当成
已验证的医学可靠性。
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT / "algorithm") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "algorithm"))

from eye_diagnosis.quality import laplacian_variance  # noqa: E402

# 标定所需的最小清晰样本量：低于这个数，统计噪声会大到阈值没有意义。
MIN_CLEAR_SAMPLES = 30

# 清晰样本的 5 分位数作为阈值：95% 的清晰样本应高于它。
CLEAR_PERCENTILE = 5.0

DEFAULT_OUTPUT = REPO_ROOT / "algorithm" / "configs" / "calibration" / "quality_blur_threshold.json"


def blur_variance(path: Path) -> float:
    with Image.open(path) as image:
        gray = np.asarray(image.convert("L").resize((380, 380)))
    return laplacian_variance(gray)


def collect(images_dir: Path) -> dict[str, float]:
    values: dict[str, float] = {}
    for path in sorted(images_dir.glob("*")):
        if path.suffix.lower() not in {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff"}:
            continue
        try:
            values[path.name] = blur_variance(path)
        except Exception as e:  # noqa: BLE001 —— 单张图读不了不该中断整批标定
            print(f"[skip] {path.name}: {e}", file=sys.stderr)
    return values


def percentile(values: list[float], pct: float) -> float:
    if not values:
        raise ValueError("没有可用的样本")
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * (pct / 100.0)
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def build_artifact(
    images_dir: Path,
    dataset_kind: str,
    values: dict[str, float],
    clear_names: list[str],
    version: str | None,
) -> dict:
    clear_values = [values[name] for name in clear_names if name in values]
    reasons: list[str] = []

    if dataset_kind != "real":
        reasons.append("数据集不是真实眼底数据（datasetKind != real），不得标定为已标定。")
    if not clear_names:
        reasons.append("缺少清晰样本清单，无法定阈值。")
    elif len(clear_values) < MIN_CLEAR_SAMPLES:
        reasons.append(
            f"清晰样本只有 {len(clear_values)} 张，少于 {MIN_CLEAR_SAMPLES} 张，统计量不可靠。"
        )

    if clear_values and not reasons:
        threshold = percentile(clear_values, CLEAR_PERCENTILE)
        state = "CALIBRATED"
        note = (
            f"阈值取清晰样本拉普拉斯响应方差的 {CLEAR_PERCENTILE:g} 分位数"
            f"（{len(clear_values)} 张清晰样本）。"
        )
    else:
        threshold = 100.0
        state = "UNCALIBRATED"
        note = "；".join(reasons) + " 阈值退回保守起点 100.0，仅供观察模式使用。"

    return {
        "version": version or f"quality-blur-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}",
        "state": state,
        "datasetKind": dataset_kind,
        "datasetName": str(images_dir),
        "sampleCount": len(values),
        "threshold": round(float(threshold), 4),
        "statistics": {
            "clear": {
                "count": len(clear_values),
                "percentile5": round(percentile(clear_values, CLEAR_PERCENTILE), 4)
                if clear_values
                else None,
                "median": round(statistics.median(clear_values), 4) if clear_values else None,
                "min": round(min(clear_values), 4) if clear_values else None,
            },
            "defocused": {"count": len(values) - len(clear_values)},
        },
        "tool": "algorithm/tools/calibrate_quality.py",
        "note": note
        + " 重标后请同步提升 INFERENCE_POLICY_VERSION，否则推理缓存会继续复用旧产物下的结果。",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="重标质量门模糊阈值并写出版本化产物")
    parser.add_argument("--images", required=True, help="图像目录")
    parser.add_argument("--clear-list", help="清晰样本清单（每行一个文件名）")
    parser.add_argument("--dataset-kind", default="synthetic",
                        choices=["synthetic", "real"], help="数据集类型；只有 real 才可能标定成功")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT), help="产物输出路径")
    parser.add_argument("--version", help="产物版本号（默认按时间戳生成）")
    parser.add_argument("--dry-run", action="store_true", help="只打印产物，不写文件")
    args = parser.parse_args()

    images_dir = Path(args.images)
    if not images_dir.is_dir():
        print(f"图像目录不存在: {images_dir}", file=sys.stderr)
        return 2

    values = collect(images_dir)
    if not values:
        print("目录里没有可用图像。", file=sys.stderr)
        return 2

    clear_names: list[str] = []
    if args.clear_list:
        clear_path = Path(args.clear_list)
        if not clear_path.is_file():
            print(f"清晰样本清单不存在: {clear_path}", file=sys.stderr)
            return 2
        clear_names = [
            line.strip()
            for line in clear_path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]

    artifact = build_artifact(images_dir, args.dataset_kind, values, clear_names, args.version)
    print(json.dumps(artifact, ensure_ascii=False, indent=2))

    if args.dry_run:
        return 0
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已写出产物: {output} (state={artifact['state']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
