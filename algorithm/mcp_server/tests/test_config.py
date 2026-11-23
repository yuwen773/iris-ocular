import os
import pytest
from unittest.mock import patch


def test_default_config():
    from mcp_server.config import MCPConfig

    config = MCPConfig()
    # 默认只绑回环：无鉴权的诊断端口不该默认出现在网卡上。
    assert config.host == "127.0.0.1"
    assert config.binds_publicly is False
    assert config.auth_token is None
    assert config.image_root is None
    assert config.port == 8000
    assert config.model_arch == "efficientnet_b4"
    assert config.num_classes == 8
    assert config.log_level == "INFO"


def test_public_bind_without_token_is_refused():
    from mcp_server.config import MCPConfig

    with pytest.raises(ValueError, match="MCP_AUTH_TOKEN"):
        MCPConfig(host="0.0.0.0")
    with pytest.raises(ValueError, match="MCP_AUTH_TOKEN"):
        MCPConfig(host="192.168.1.20")
    # 配了令牌才允许对外绑定。
    config = MCPConfig(host="0.0.0.0", auth_token="s3cret")
    assert config.binds_publicly is True
    assert "s3cret" not in repr(config)


def test_from_env_reads_security_settings():
    from mcp_server.config import MCPConfig

    with patch.dict(
        os.environ,
        {"MCP_HOST": "0.0.0.0", "MCP_AUTH_TOKEN": "token-1", "MCP_IMAGE_ROOT": "/srv/images"},
        clear=False,
    ):
        config = MCPConfig.from_env()
    assert config.host == "0.0.0.0"
    assert config.auth_token == "token-1"
    assert config.image_root == "/srv/images"


def test_config_from_env():
    from mcp_server.config import MCPConfig

    env = {
        "MCP_HOST": "127.0.0.1",
        "MCP_PORT": "9000",
        "MODEL_ARCH": "resnet50",
        "DEVICE": "cpu",
        "NUM_CLASSES": "10",
        "LOG_LEVEL": "DEBUG",
    }
    with patch.dict(os.environ, env, clear=False):
        config = MCPConfig.from_env()
    assert config.host == "127.0.0.1"
    assert config.port == 9000
    assert config.model_path is None  # MODEL_PATH not in env, should be None
    assert config.model_arch == "resnet50"
    assert config.device == "cpu"
    assert config.num_classes == 10
    assert config.log_level == "DEBUG"


def test_config_device_auto():
    from mcp_server.config import MCPConfig

    config = MCPConfig()
    assert config.device in ("cuda", "cpu")


def test_config_invalid_port():
    from mcp_server.config import MCPConfig
    with pytest.raises(ValueError):
        MCPConfig(port=-1)