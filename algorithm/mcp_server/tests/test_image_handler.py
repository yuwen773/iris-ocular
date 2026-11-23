import os
import base64
import tempfile
import pytest
from unittest.mock import patch, MagicMock, AsyncMock
from pathlib import Path

from mcp_server.image_handler import resolve_image


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
async def test_resolve_http_url(monkeypatch):
    img_bytes = _make_test_image_bytes()
    mock_response = MagicMock()
    mock_response.content = img_bytes
    mock_response.raise_for_status = MagicMock()

    mock_client = AsyncMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    mock_client.get = AsyncMock(return_value=mock_response)

    # 解析结果固定成公网地址：用例不依赖真实 DNS，但 SSRF 校验仍然生效。
    monkeypatch.setattr(
        "mcp_server.image_handler._resolve_host_ips",
        lambda host, port: {"93.184.216.34"},
    )
    with patch("mcp_server.image_handler._get_client", return_value=mock_client):
        result_path = await resolve_image("https://example.com/eye.png")
        mock_client.get.assert_called_once_with("https://example.com/eye.png", timeout=30)
        assert os.path.exists(result_path)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "address",
    ["127.0.0.1", "10.0.0.5", "192.168.1.10", "169.254.169.254", "::1", "::ffff:127.0.0.1"],
)
async def test_private_and_metadata_targets_are_refused(address, monkeypatch):
    # 内网、回环、链路本地（含云元数据 169.254.169.254）一律拒绝：
    # 这个工具不接受「让服务端替调用方访问内网」。
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
