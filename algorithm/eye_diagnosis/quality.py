from __future__ import annotations

import io
import os
from dataclasses import dataclass
from enum import Enum
from typing import List

import numpy as np
from PIL import Image

# 模糊判据：拉普拉斯响应方差低于该值即视为模糊。
#
# 这是**启发式阈值，不是经过真实数据验证的医学阈值**。旧实现的量纲是错的
# （灰度 × 响应），12.0 从来没有意义；换成正确的方差后按 8bit 眼底图的常见量级
# 取 100。上线前必须在真实数据集上重标（清晰/离焦分层统计），重标结果写进
# 校准与验证证据；在此之前质量门默认只以 observe 模式运行（QUALITY_GATE_MODE）。
BLUR_VARIANCE_MIN = float(os.getenv("QUALITY_BLUR_VARIANCE_MIN", "100.0"))


class QualityVerdict(str, Enum):
    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"


@dataclass(frozen=True)
class QualityReport:
    score: int
    blur: float
    brightness: float
    contrast: float
    fundus_coverage: float
    lateral_sign: str
    reasons: List[str]

    @property
    def verdict(self) -> QualityVerdict:
        return QualityVerdict.REJECTED if self.reasons else QualityVerdict.ACCEPTED


def _laplacian_variance(gray: np.ndarray) -> float:
    """拉普拉斯响应的方差（与 OpenCV `cv2.Laplacian(img, CV_64F).var()` 同一定义）。

    4-邻域离散拉普拉斯核：中心 -4，上下左右各 +1。直接对灰度图做卷积再取方差，
    不做「灰度 × 响应」——那个量纲混着亮度，阈值没有物理含义，正是旧实现的错误。
    这里用切片实现，不引入 scipy / opencv 依赖；边缘一圈不参与统计（卷积在边界
    没有完整邻域），所以结果与 cv2 的全图方差略有出入，但判据一致。

    对焦清晰的眼底图，这个值通常在数百以上；离焦、运动模糊会显著降低。
    """
    g = gray.astype(np.float32)
    response = -4.0 * g
    response[1:, :] += g[:-1, :]
    response[:-1, :] += g[1:, :]
    response[:, 1:] += g[:, :-1]
    response[:, :-1] += g[:, 1:]
    return float(response[1:-1, 1:-1].var())


def evaluate_quality(image_bytes: bytes) -> QualityReport:
    image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    gray_l = np.asarray(image.convert("L").resize((380, 380)))

    brightness = float(gray_l.mean()) / 255.0
    contrast = float(gray_l.std()) / 128.0
    blur = _laplacian_variance(gray_l)
    h, w = gray_l.shape
    central = gray_l[h // 4 : 3 * h // 4, w // 4 : 3 * w // 4]
    coverage = float(gray_l.mean()) / max(float(central.mean()), 1.0)

    reasons: List[str] = []
    if blur < BLUR_VARIANCE_MIN:
        reasons.append("blur")
    if brightness < 0.15 or brightness > 0.92:
        reasons.append("brightness")
    if contrast < 0.08:
        reasons.append("contrast")
    if coverage < 1.05:
        reasons.append("fundus_coverage")
    lateral_sign = "unknown"

    score = int(round(100 - 25 * len(reasons)))
    return QualityReport(
        score=max(score, 0),
        blur=blur,
        brightness=brightness,
        contrast=contrast,
        fundus_coverage=coverage,
        lateral_sign=lateral_sign,
        reasons=reasons,
    )
