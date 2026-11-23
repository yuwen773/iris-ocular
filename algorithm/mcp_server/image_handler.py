import base64
import ipaddress
import logging
import os
import socket
import tempfile
import urllib.parse
from pathlib import Path

import httpx

from mcp_server.image_ref import ImageReferenceError, is_reference, verify

logger = logging.getLogger(__name__)

_IMAGE_SUFFIXES = frozenset((".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff", ".webp"))
_TEMP_SUFFIX_MAP = {".png", ".bmp", ".tif", ".tiff", ".webp"}

# 下载上限：先看 Content-Length，再按实际字节数截断。
_MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024

_http_client: httpx.AsyncClient | None = None


async def _get_client() -> httpx.AsyncClient:
    global _http_client
    if _http_client is None or _http_client.is_closed:
        # 不跟随重定向：跳转目标会绕过这里的公网地址校验。
        _http_client = httpx.AsyncClient(follow_redirects=False)
    return _http_client


def _remote_urls_allowed() -> bool:
    """是否显式放开「任意远程图片 URL」。

    默认关闭。过去这个工具读模型给的任何 URL，等于把服务变成内网探测器与
    公网抓取器；现在默认只认后端签发的短期引用，确需读任意 URL 的部署要自己
    打开这个开关，并且知道 SSRF 过滤此时仍然生效。
    """
    return os.getenv("MCP_ALLOW_REMOTE_URLS", "").strip().lower() in {"1", "true", "yes", "on"}


def remote_urls_allowed() -> bool:
    """对外暴露的开关状态（启动日志用，不含任何密钥）。"""
    return _remote_urls_allowed()


def reference_secret_configured() -> bool:
    """是否配置了图片引用密钥（只报有没有，不报内容）。"""
    return bool(_reference_secret())


def _reference_secret() -> str:
    return os.getenv("MCP_IMAGE_REF_SECRET", "").strip()


async def resolve_image(image_input: str) -> str:
    """Resolve image input to a local file path.

    Accepts:
    - Backend-issued short-lived reference (``iris-img://...``) — the default and
      preferred form: signed by the backend, expires quickly, cannot be forged.
    - HTTP/HTTPS URL, only when ``MCP_ALLOW_REMOTE_URLS`` is explicitly enabled
    - Local file path, only inside MCP_IMAGE_ROOT when that variable is set
    - Base64 encoded image data
    """
    if is_reference(image_input):
        return await _resolve_reference(image_input)

    if image_input.startswith(("http://", "https://")):
        if not _remote_urls_allowed():
            raise ValueError(
                "默认不接受任意远程图片 URL：请改用后端签发的短期图片引用（iris-img://…），"
                "确需读取任意 URL 时显式设置 MCP_ALLOW_REMOTE_URLS=true"
            )
        return await _resolve_url(image_input)

    if _looks_like_filepath(image_input):
        return _resolve_filepath(image_input)

    return _resolve_base64(image_input)


async def _resolve_reference(reference: str) -> str:
    """按后端签发的引用取图。

    签名本身即授权：目标地址由后端决定，因此不再套用「必须是公网地址」的限制
    （后端有权把图指向内网服务）。仍然限制协议、体积，并且不跟随重定向。
    """
    try:
        target = verify(reference, _reference_secret())
    except ImageReferenceError as e:
        raise ValueError(f"图片引用不可用: {e}") from e

    host = urllib.parse.urlsplit(target).hostname
    try:
        client = await _get_client()
        resp = await client.get(target, timeout=30)
        resp.raise_for_status()
    except httpx.HTTPError as e:
        raise ValueError(f"下载图片失败: {e}") from e
    content = resp.content
    if len(content) > _MAX_DOWNLOAD_BYTES:
        raise ValueError("下载图片超过 50MB 限制")
    # 只记主机名：签名与目标地址都不进日志（目标里带签名参数）。
    logger.info("已按后端签发的引用取图: host=%s", host)
    return _write_temp(content, target)


def _looks_like_filepath(s: str) -> bool:
    if not s:
        return False
    if s.startswith("/") or (len(s) >= 2 and s[1] == ":"):
        return True
    ext = Path(s).suffix.lower()
    return ext in _IMAGE_SUFFIXES


def _resolve_host_ips(host: str, port: int) -> set[str]:
    """解析主机名到 IP 集合（测试里替换掉这一层，避免依赖真实 DNS）。"""
    infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    return {info[4][0] for info in infos}


def _is_public_ip(raw: str) -> bool:
    try:
        address = ipaddress.ip_address(raw)
    except ValueError:
        return False
    # ::ffff:127.0.0.1 这类 IPv4-mapped 地址按内层地址判断。
    mapped = getattr(address, "ipv4_mapped", None)
    if mapped is not None:
        address = mapped
    return not (
        address.is_private
        or address.is_loopback
        or address.is_link_local
        or address.is_multicast
        or address.is_reserved
        or address.is_unspecified
    )


def assert_public_url(url: str) -> None:
    """只允许 http/https，且解析出的每个地址都必须是公网地址。

    这条校验挡住「让服务端替我访问内网」：没有它，这个工具就是一个内网探测器
    （云元数据地址、管理口、K8s API 都能被打）。DNS 重绑定（校验时公网、连接时内网）
    仍然存在，彻底的解法是把连接固定到已校验的 IP；这里先把最常见的形态挡住，
    并把残余风险写在注释里，不假装已经解决。
    """
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError(f"只允许 http/https 图片地址: {parsed.scheme}")
    host = parsed.hostname
    if not host:
        raise ValueError("图片 URL 缺少主机名")
    try:
        default_port = 443 if parsed.scheme == "https" else 80
        addresses = _resolve_host_ips(host, parsed.port or default_port)
    except OSError as e:
        raise ValueError(f"无法解析图片主机名: {host}") from e
    if not addresses:
        raise ValueError(f"图片主机名没有解析出地址: {host}")
    for address in sorted(addresses):
        if not _is_public_ip(address):
            raise ValueError(f"拒绝访问非公网地址: {host} → {address}")


async def _resolve_url(url: str) -> str:
    assert_public_url(url)
    client = await _get_client()
    try:
        resp = await client.get(url, timeout=30)
        resp.raise_for_status()
    except httpx.HTTPError as e:
        raise ValueError(f"下载图片失败: {e}") from e
    content = resp.content
    if len(content) > _MAX_DOWNLOAD_BYTES:
        raise ValueError("下载图片超过 50MB 限制")
    return _write_temp(content, url)


def _image_root() -> Path | None:
    root = os.getenv("MCP_IMAGE_ROOT")
    return Path(root).resolve() if root else None


def _resolve_filepath(path: str) -> str:
    """本地路径只允许读 MCP_IMAGE_ROOT 之内的文件。

    未设置 MCP_IMAGE_ROOT 时直接拒绝：默认开放「任意可读文件」等于把服务端文件系统
    交给调用方当图片加载器。路径先 resolve 再比较，`..` 与符号链接都逃不出去。
    """
    root = _image_root()
    if root is None:
        raise ValueError("本地文件路径输入已禁用：需要设置 MCP_IMAGE_ROOT 指定允许读取的目录")
    resolved = Path(path).resolve()
    try:
        resolved.relative_to(root)
    except ValueError as e:
        raise ValueError(f"图片路径不在允许的目录内: {path}") from e
    if not resolved.is_file():
        raise FileNotFoundError(f"图片文件不存在: {path}")
    return str(resolved)


def _resolve_base64(data: str) -> str:
    if len(data) > 100 * 1024 * 1024:
        raise ValueError("Base64 输入数据过大")
    try:
        raw = base64.b64decode(data, validate=True)
    except Exception as e:
        raise ValueError(f"Base64 解码失败: {e}") from e
    if len(raw) < 4:
        raise ValueError("Base64 解码后数据过短，不是有效的图片")
    return _write_temp(raw, "base64")


def _write_temp(data: bytes, source_hint: str) -> str:
    suffix = ".jpg"
    ext = Path(source_hint.split("?")[0]).suffix.lower()
    if ext in _TEMP_SUFFIX_MAP:
        suffix = ext
    fd, tmp_path = tempfile.mkstemp(suffix=suffix)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    return tmp_path
