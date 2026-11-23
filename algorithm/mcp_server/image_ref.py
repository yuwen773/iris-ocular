"""后端签发的短期图片引用。

格式：``iris-img://<payload>?exp=<unix 秒>&sig=<hex>``

* ``payload``：``base64url``（无填充）编码的目标地址。
* ``sig``：``HMAC-SHA256(secret, payload + "|" + exp)`` 的十六进制。

为什么要有这层：MCP 工具过去接受「模型给什么 URL 就读什么」，于是这个服务同时是
内网探测器（能抓 169.254.169.254）和公网抓取器。引用把这个口子关成「只认后端
签发、且很快过期的东西」：签名由后端持有，模型无法伪造；有效期短，泄漏的引用
很快失效。

约定：
* 校验失败一律抛 :class:`ValueError`，不降级、不猜。
* 引用本身即授权：目标地址由签发方（后端）决定，因此不再套用「必须是公网地址」
  的限制；但仍然限制协议、下载体积，并且不跟随重定向。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import time
import urllib.parse

SCHEME = "iris-img://"

# 引用有效期上限（秒）：即便签发方给更长 TTL，校验时也按这个上限拒绝。
MAX_TTL_SECONDS = 15 * 60


class ImageReferenceError(ValueError):
    """引用不可用（格式错误、签名不符、已过期或未配置密钥）。"""


def _b64encode(raw: str) -> str:
    return base64.urlsafe_b64encode(raw.encode("utf-8")).decode("ascii").rstrip("=")


def _b64decode(payload: str) -> str:
    padding = "=" * (-len(payload) % 4)
    try:
        return base64.urlsafe_b64decode(payload + padding).decode("utf-8")
    except Exception as e:  # noqa: BLE001 —— 任何解码失败都归一成引用不可用
        raise ImageReferenceError("图片引用的载荷不是合法的 base64url") from e


def _signature(secret: str, payload: str, expires_at: int) -> str:
    message = f"{payload}|{expires_at}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()


def sign(target: str, secret: str, ttl_seconds: int, now: int | None = None) -> str:
    """签发一个短期引用。签发方（后端）使用；Python 侧只做校验。"""
    if not secret:
        raise ImageReferenceError("缺少图片引用密钥，无法签发")
    if not target or not target.startswith(("http://", "https://")):
        raise ImageReferenceError("图片引用只支持 http/https 目标")
    if ttl_seconds <= 0 or ttl_seconds > MAX_TTL_SECONDS:
        raise ImageReferenceError(f"图片引用有效期必须在 1..{MAX_TTL_SECONDS} 秒之间")
    issued_at = int(time.time()) if now is None else now
    expires_at = issued_at + ttl_seconds
    payload = _b64encode(target)
    signature = _signature(secret, payload, expires_at)
    return f"{SCHEME}{payload}?exp={expires_at}&sig={signature}"


def is_reference(value: str) -> bool:
    return isinstance(value, str) and value.startswith(SCHEME)


def verify(reference: str, secret: str, now: int | None = None) -> str:
    """校验引用并返回其中的目标地址。

    任何一步不通过都抛 :class:`ImageReferenceError`：签名不符、已过期、
    格式不对、没有配置密钥——一律不放行。
    """
    if not secret:
        raise ImageReferenceError(
            "未配置图片引用密钥（MCP_IMAGE_REF_SECRET / IMAGE_REFERENCE_SECRET），拒绝使用图片引用"
        )
    if not is_reference(reference):
        raise ImageReferenceError("不是合法的图片引用")

    body = reference[len(SCHEME):]
    if "?" not in body:
        raise ImageReferenceError("图片引用缺少 exp 或 sig")
    payload, query = body.split("?", 1)
    params = urllib.parse.parse_qs(query)
    expires_raw = (params.get("exp") or [""])[0]
    signature = (params.get("sig") or [""])[0]
    if not payload or not expires_raw or not signature:
        raise ImageReferenceError("图片引用缺少 exp 或 sig")

    try:
        expires_at = int(expires_raw)
    except ValueError as e:
        raise ImageReferenceError("图片引用的 exp 不是时间戳") from e

    expected = _signature(secret, payload, expires_at)
    if not hmac.compare_digest(expected, signature):
        raise ImageReferenceError("图片引用签名校验失败")

    current = int(time.time()) if now is None else now
    if current > expires_at:
        raise ImageReferenceError("图片引用已过期")
    if expires_at - current > MAX_TTL_SECONDS:
        raise ImageReferenceError("图片引用有效期超出上限")

    target = _b64decode(payload)
    if not target.startswith(("http://", "https://")):
        raise ImageReferenceError("图片引用指向的地址不是 http/https")
    return target
