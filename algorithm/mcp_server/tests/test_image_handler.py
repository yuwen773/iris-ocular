import os
import base64
import tempfile
import pytest
from unittest.mock import patch, MagicMock, AsyncMock
from pathlib import Path

from mcp_server import image_ref
from mcp_server.image_handler import resolve_image


class _FakeStream:
    """httpx 流式响应的替身：实现 ``async with`` 与 ``aiter_bytes``。"""

    def __init__(self, content: bytes, headers: dict | None = None, status_code: int = 200):
        self._content = content
        self.headers = headers or {}
        self.status_code = status_code

    @property
    def is_redirect(self) -> bool:
        return 300 <= self.status_code < 400

    def raise_for_status(self) -> None:
        return None

    async def aiter_bytes(self):
        yield self._content

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info):
        return False


def _make_test_image_bytes() -> bytes:
    """Minimal 1x1 red PNG."""
    import struct, zlib
    def _chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    header = b"\x89PNG\r\n\x1a\n"
    ihdr = _chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
    raw = zlib.compress(b"\x00\xff\x00\x00")
    idat = _chunk(b"IDAT", raw)
    iend = _chunk(b"IEND", b"")
    return header + ihdr + idat + iend


@pytest.mark.asyncio
async def test_resolve_filepath(tmp_path, monkeypatch):
    monkeypatch.setenv("MCP_IMAGE_ROOT", str(tmp_path))
    img = tmp_path / "test.png"
    img.write_bytes(_make_test_image_bytes())
    result_path = await resolve_image(str(img))
    assert result_path == str(img.resolve())


@pytest.mark.asyncio
async def test_local_filepath_is_disabled_without_image_root(tmp_path, monkeypatch):
    # 默认不开放本地路径：否则这个工具就是一台「任意可读文件 → 图片加载器」。
    monkeypatch.delenv("MCP_IMAGE_ROOT", raising=False)
    img = tmp_path / "test.png"
    img.write_bytes(_make_test_image_bytes())
    with pytest.raises(ValueError, match="MCP_IMAGE_ROOT"):
        await resolve_image(str(img))


@pytest.mark.asyncio
async def test_local_filepath_outside_image_root_is_rejected(tmp_path, monkeypatch):
    allowed = tmp_path / "allowed"
    allowed.mkdir()
    monkeypatch.setenv("MCP_IMAGE_ROOT", str(allowed))
    outside = tmp_path / "outside.png"
    outside.write_bytes(_make_test_image_bytes())
    with pytest.raises(ValueError, match="不在允许的目录内"):
        await resolve_image(str(outside))
    # `..` 也要逃不出去：校验发生在 resolve() 之后。
    nested = allowed / ".." / "outside.png"
    with pytest.raises(ValueError, match="不在允许的目录内"):
        await resolve_image(str(nested))


@pytest.mark.asyncio
async def test_resolve_base64():
    data = base64.b64encode(_make_test_image_bytes()).decode()
    result_path = await resolve_image(data)
    assert os.path.exists(result_path)


@pytest.mark.asyncio
async def test_arbitrary_remote_url_is_refused_by_default(monkeypatch):
    # 默认只认后端签发的短期引用：模型给的任意 URL 不再被直接读取。
    monkeypatch.delenv("MCP_ALLOW_REMOTE_URLS", raising=False)
    with pytest.raises(ValueError, match="MCP_ALLOW_REMOTE_URLS"):
        await resolve_image("https://example.com/eye.png")


@pytest.mark.asyncio
async def test_resolve_http_url(monkeypatch):
    # 显式放开任意 URL 后，SSRF 过滤仍然生效。
    monkeypatch.setenv("MCP_ALLOW_REMOTE_URLS", "true")
    img_bytes = _make_test_image_bytes()

    mock_client = AsyncMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client.stream = MagicMock(return_value=_FakeStream(img_bytes))

    # 解析结果固定成公网地址：用例不依赖真实 DNS，但 SSRF 校验仍然生效。
    monkeypatch.setattr(
        "mcp_server.image_handler._resolve_host_ips",
        lambda host, port: {"93.184.216.34"},
    )
    with patch("mcp_server.image_handler._get_client", return_value=mock_client):
        result_path = await resolve_image("https://example.com/eye.png")
        assert mock_client.stream.call_args.args == ("GET", "https://example.com/eye.png")
        assert os.path.exists(result_path)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "address",
    ["127.0.0.1", "10.0.0.5", "192.168.1.10", "169.254.169.254", "::1", "::ffff:127.0.0.1"],
)
async def test_private_and_metadata_targets_are_refused(address, monkeypatch):
    # 内网、回环、链路本地（含云元数据 169.254.169.254）一律拒绝：
    # 这个工具不接受「让服务端替调用方访问内网」。
    monkeypatch.setenv("MCP_ALLOW_REMOTE_URLS", "true")
    monkeypatch.setattr(
        "mcp_server.image_handler._resolve_host_ips",
        lambda host, port: {address},
    )
    with pytest.raises(ValueError, match="非公网"):
        await resolve_image("http://internal.example/eye.png")


@pytest.mark.asyncio
async def test_url_with_non_http_scheme_is_refused():
    with pytest.raises(ValueError):
        await resolve_image("ftp://example.com/eye.png")


@pytest.mark.asyncio
async def test_resolve_invalid_base64():
    with pytest.raises(ValueError, match="Base64"):
        await resolve_image("not-valid-base64!!!")


@pytest.mark.asyncio
async def test_resolve_missing_file(tmp_path, monkeypatch):
    monkeypatch.setenv("MCP_IMAGE_ROOT", str(tmp_path))
    with pytest.raises(FileNotFoundError):
        await resolve_image(str(tmp_path / "missing.png"))


@pytest.mark.asyncio
async def test_signed_reference_is_accepted(monkeypatch):
    # 后端签发的短期引用是首选输入：签名有效即可取图，不再要求目标必须是公网地址
    # （目标由后端决定，签名本身就是授权）。
    monkeypatch.setenv("MCP_IMAGE_REF_SECRET", "s3cret")
    reference = image_ref.sign("https://cos.example.com/agent-chat/7/a.png", "s3cret", 300)

    mock_client = AsyncMock()
    mock_client.stream = MagicMock(return_value=_FakeStream(_make_test_image_bytes()))

    with patch("mcp_server.image_handler._get_client", return_value=mock_client):
        result_path = await resolve_image(reference)
        assert os.path.exists(result_path)
        assert mock_client.stream.call_args.args[1] == "https://cos.example.com/agent-chat/7/a.png"


@pytest.mark.asyncio
async def test_oversized_response_is_refused_from_content_length(monkeypatch):
    # 声明了超大 Content-Length 就不必把正文收下来。
    monkeypatch.setenv("MCP_ALLOW_REMOTE_URLS", "true")
    monkeypatch.setattr(
        "mcp_server.image_handler._resolve_host_ips",
        lambda host, port: {"93.184.216.34"},
    )
    oversized = str(60 * 1024 * 1024)
    mock_client = AsyncMock()
    mock_client.stream = MagicMock(
        return_value=_FakeStream(b"x", headers={"content-length": oversized})
    )
    with patch("mcp_server.image_handler._get_client", return_value=mock_client):
        with pytest.raises(ValueError, match="50MB"):
            await resolve_image("https://example.com/huge.png")


@pytest.mark.asyncio
async def test_streaming_download_stops_at_the_size_cap(monkeypatch):
    # 对方不声明长度也不能把内存吃光：边下边数，超过上限立刻中止。
    monkeypatch.setenv("MCP_ALLOW_REMOTE_URLS", "true")
    monkeypatch.setattr("mcp_server.image_handler._MAX_DOWNLOAD_BYTES", 8)
    monkeypatch.setattr(
        "mcp_server.image_handler._resolve_host_ips",
        lambda host, port: {"93.184.216.34"},
    )
    mock_client = AsyncMock()
    mock_client.stream = MagicMock(return_value=_FakeStream(b"0123456789"))
    with patch("mcp_server.image_handler._get_client", return_value=mock_client):
        with pytest.raises(ValueError, match="50MB"):
            await resolve_image("https://example.com/huge.png")


@pytest.mark.asyncio
async def test_redirect_response_is_refused_instead_of_downloaded(monkeypatch):
    # 不跟随重定向是安全策略；3xx 的正文也不是图片，收下来只会在解码阶段炸，
    # 或者更糟——写下一个「看起来成功」却不是图的对象。要明确拒绝。
    monkeypatch.setenv("MCP_ALLOW_REMOTE_URLS", "true")
    monkeypatch.setattr(
        "mcp_server.image_handler._resolve_host_ips",
        lambda host, port: {"93.184.216.34"},
    )
    mock_client = AsyncMock()
    mock_client.stream = MagicMock(return_value=_FakeStream(b"<html>", status_code=302))
    with patch("mcp_server.image_handler._get_client", return_value=mock_client):
        with pytest.raises(ValueError, match="重定向"):
            await resolve_image("https://example.com/eye.png")


@pytest.mark.asyncio
async def test_reference_without_configured_secret_is_refused(monkeypatch):
    monkeypatch.delenv("MCP_IMAGE_REF_SECRET", raising=False)
    monkeypatch.delenv("IMAGE_REFERENCE_SECRET", raising=False)
    reference = image_ref.sign("https://cos.example.com/a.png", "s3cret", 300)
    with pytest.raises(ValueError, match="图片引用不可用"):
        await resolve_image(reference)


@pytest.mark.asyncio
async def test_reference_with_broken_signature_is_refused(monkeypatch):
    monkeypatch.setenv("MCP_IMAGE_REF_SECRET", "s3cret")
    reference = image_ref.sign("https://cos.example.com/a.png", "other-secret", 300)
    with pytest.raises(ValueError, match="图片引用不可用"):
        await resolve_image(reference)
