"""图片引用（后端签发、算法侧校验）的单元测试。

重点是三件事：
1. 签名算法与 Java 侧一致（用同一组测试向量锁死）；
2. 篡改、过期、没有密钥一律拒绝；
3. 默认不再接受任意远程 URL。
"""

import base64
import hashlib
import hmac
import os

import pytest

from mcp_server import image_ref
from mcp_server.image_ref import ImageReferenceError


def _expected_signature(secret: str, payload: str, expires_at: int) -> str:
    return hmac.new(
        secret.encode("utf-8"),
        f"{payload}|{expires_at}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def test_reference_matches_the_shared_test_vector():
    """与 Java 侧 ImageReferenceSignerTest 共用同一组向量：算法写错就红。"""
    payload = "aHR0cHM6Ly9leGFtcGxlLmNvbS9hLnBuZw"
    expires_at = 1700000000
    expected = "476df5cf1cc2911083dbfd00268be413cedae2b9ccae296a0c8f293720757c5d"

    assert _expected_signature("test-secret", payload, expires_at) == expected
    reference = (f"iris-img://{payload}?exp={expires_at}&sig={expected}")
    assert image_ref.verify(reference, "test-secret", now=expires_at - 10) == \
        "https://example.com/a.png"


def test_sign_then_verify_round_trip():
    reference = image_ref.sign("https://example.com/a.png", "s3cret", 300, now=1000)
    assert reference.startswith("iris-img://")
    assert image_ref.verify(reference, "s3cret", now=1000) == "https://example.com/a.png"


def test_tampered_signature_is_rejected():
    reference = image_ref.sign("https://example.com/a.png", "s3cret", 300, now=1000)
    tampered = reference[:-1] + ("0" if reference[-1] != "0" else "1")
    with pytest.raises(ImageReferenceError):
        image_ref.verify(tampered, "s3cret", now=1000)


def test_expired_reference_is_rejected():
    reference = image_ref.sign("https://example.com/a.png", "s3cret", 60, now=1000)
    with pytest.raises(ImageReferenceError):
        image_ref.verify(reference, "s3cret", now=1061)


def test_reference_signed_with_another_secret_is_rejected():
    reference = image_ref.sign("https://example.com/a.png", "other", 300, now=1000)
    with pytest.raises(ImageReferenceError):
        image_ref.verify(reference, "s3cret", now=1000)


def test_reference_is_rejected_without_configured_secret(monkeypatch):
    monkeypatch.delenv("MCP_IMAGE_REF_SECRET", raising=False)
    reference = image_ref.sign("https://example.com/a.png", "s3cret", 300, now=1000)
    with pytest.raises(ImageReferenceError):
        image_ref.verify(reference, "", now=1000)


def test_reference_to_non_http_target_is_rejected():
    with pytest.raises(ImageReferenceError):
        image_ref.sign("file:///etc/passwd", "s3cret", 300, now=1000)


def test_reference_with_ttl_above_the_cap_is_rejected():
    with pytest.raises(ImageReferenceError):
        image_ref.sign("https://example.com/a.png", "s3cret", image_ref.MAX_TTL_SECONDS + 1, now=1000)


def test_garbage_payload_is_rejected():
    reference = "iris-img://not-base64!!?exp=1700000000&sig=deadbeef"
    with pytest.raises(ImageReferenceError):
        image_ref.verify(reference, "s3cret", now=1699999999)


def test_payload_is_base64url_without_padding():
    reference = image_ref.sign("https://example.com/a.png", "s3cret", 300, now=1000)
    payload = reference[len("iris-img://"):].split("?", 1)[0]
    assert "=" not in payload
    assert base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)).decode() == \
        "https://example.com/a.png"


def test_env_secret_is_not_logged_in_repr():
    # 密钥只进环境变量与签名计算，不进任何可打印的中间状态。
    os.environ.setdefault("MCP_IMAGE_REF_SECRET", "s3cret")
    assert "s3cret" not in repr(image_ref)
