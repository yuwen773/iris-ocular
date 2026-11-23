import os
import torch


class MCPConfig:
    """MCP 服务配置。

    安全默认值：只绑回环地址。想让它被别的机器连上，必须同时提供 `MCP_AUTH_TOKEN`——
    否则这个端口就是一个无鉴权的「任意图片 → 诊断」入口，而工具还接受 URL 与本地路径。
    """

    LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 8000,
        model_path: str | None = None,
        model_arch: str = "efficientnet_b4",
        device: str | None = None,
        num_classes: int = 8,
        log_level: str = "INFO",
        auth_token: str | None = None,
        image_root: str | None = None,
    ):
        self.host = host
        if not isinstance(port, int) or port < 1 or port > 65535:
            raise ValueError(f"port must be an integer between 1 and 65535, got {port!r}")
        self.port = port
        self.model_path = model_path
        self.model_arch = model_arch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.num_classes = num_classes
        self.log_level = log_level
        self.auth_token = auth_token or None
        self.image_root = image_root or None
        if self.binds_publicly and not self.auth_token:
            raise ValueError(
                f"拒绝在 {host}:{port} 上无鉴权启动：对外绑定必须设置 MCP_AUTH_TOKEN"
                "（或者把 MCP_HOST 设回 127.0.0.1，由本机反向代理负责鉴权）"
            )

    @property
    def binds_publicly(self) -> bool:
        return (self.host or "").strip().lower() not in self.LOOPBACK_HOSTS

    def __repr__(self) -> str:
        # 令牌不进程外可见：只写有没有配。
        return (f"MCPConfig(host={self.host!r}, port={self.port}, "
                f"model_path={self.model_path!r}, model_arch={self.model_arch!r}, "
                f"device={self.device!r}, num_classes={self.num_classes}, "
                f"log_level={self.log_level!r}, auth={'set' if self.auth_token else 'none'}, "
                f"image_root={self.image_root!r})")

    @classmethod
    def from_env(cls) -> "MCPConfig":
        return cls(
            host=os.getenv("MCP_HOST", "127.0.0.1"),
            port=int(os.getenv("MCP_PORT", "8000")),
            model_path=os.getenv("MODEL_PATH") or None,
            model_arch=os.getenv("MODEL_ARCH", "efficientnet_b4"),
            device=os.getenv("DEVICE") or None,
            num_classes=int(os.getenv("NUM_CLASSES", "8")),
            log_level=os.getenv("LOG_LEVEL", "INFO"),
            auth_token=os.getenv("MCP_AUTH_TOKEN") or None,
            image_root=os.getenv("MCP_IMAGE_ROOT") or None,
        )