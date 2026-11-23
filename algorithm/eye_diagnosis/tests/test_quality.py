import io
from PIL import Image, ImageFilter
import numpy as np

from eye_diagnosis.quality import (
    QualityState,
    QualityVerdict,
    evaluate_quality,
    laplacian_variance,
)


def _to_png(arr: np.ndarray) -> bytes:
    buf = io.BytesIO()
    Image.fromarray(arr).save(buf, format="PNG")
    return buf.getvalue()


def _make_image(w: int, h: int, value: int = 80) -> bytes:
    arr = np.full((h, w, 3), value, dtype=np.uint8)
    return _to_png(arr)


def test_uniform_image_is_rejected_for_blur():
    image = _make_image(380, 380, value=80)
    report = evaluate_quality(image)
    assert report.verdict is QualityVerdict.REJECTED
    assert "blur" in report.reasons


def test_black_image_is_rejected_for_brightness():
    image = _make_image(380, 380, value=8)
    report = evaluate_quality(image)
    assert report.verdict is QualityVerdict.REJECTED
    assert "brightness" in report.reasons


def test_typical_fundus_is_accepted():
    rng = np.random.default_rng(0)
    arr = rng.integers(60, 150, size=(380, 380, 3), dtype=np.uint8)
    arr[60:320, 60:320, :] = rng.integers(40, 120, size=(260, 260, 3), dtype=np.uint8)
    report = evaluate_quality(_to_png(arr))
    assert report.verdict is QualityVerdict.ACCEPTED
    assert 0 <= report.score <= 100


def test_laplacian_variance_is_the_response_variance_not_a_product_with_brightness():
    # 常数图：拉普拉斯响应处处为 0，方差必须恰好是 0。
    # 旧实现算的是「灰度 × 响应」的方差，常数图同样为 0，所以这一条旧代码也能过；
    # 下一条才是真正区分两种定义的用例。
    assert laplacian_variance(np.full((32, 32), 90, dtype=np.uint8)) == 0.0
    # 均匀噪声：响应方差很大（万级），而「灰度 × 响应」会把亮度因子乘进去，
    # 量级随亮度漂移。两种定义在这里相差一个 255 倍的量级差。
    rng = np.random.default_rng(1)
    noise = rng.integers(0, 255, size=(64, 64), dtype=np.uint8)
    assert laplacian_variance(noise) > 1000


def test_gaussian_blur_collapseslaplacian_variance():
    rng = np.random.default_rng(2)
    arr = rng.integers(60, 180, size=(380, 380), dtype=np.uint8)
    sharp = laplacian_variance(arr)
    blurred = laplacian_variance(
        np.asarray(Image.fromarray(arr).filter(ImageFilter.GaussianBlur(radius=6)))
    )
    assert sharp > 1000
    assert blurred < sharp / 20


def test_state_is_the_single_verdict_field():
    # 「判了失败」与「为什么失败」只能有一个来源：线上协议读 state，不读 reasons。
    # 这两者一旦分叉，就会出现「state=PASSED 但 reasons 非空」这种自相矛盾的响应。
    for value in (80, 8):
        report = evaluate_quality(_make_image(380, 380, value=value))
        expected = QualityState.FAILED if report.reasons else QualityState.PASSED
        assert report.state is expected
        assert (report.state is QualityState.PASSED) == (report.verdict is QualityVerdict.ACCEPTED)
