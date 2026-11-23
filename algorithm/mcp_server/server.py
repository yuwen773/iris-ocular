import logging

from mcp.server.fastmcp import FastMCP

from mcp_server.auth import BearerTokenMiddleware
from mcp_server.config import MCPConfig
from mcp_server.image_handler import reference_secret_configured, remote_urls_allowed
from mcp_server.tools import diagnose, load_model

logger = logging.getLogger(__name__)

mcp = FastMCP(
    "Eye Disease Diagnosis",
    instructions="眼科疾病AI诊断服务。提供眼底图像分析，可识别8种常见眼科疾病：正常、年龄相关性黄斑变性、白内障、糖尿病视网膜病变、青光眼、高血压视网膜病变、病理性近视、其他疾病。",
)


@mcp.tool()
async def eye_disease_diagnosis(image_input: str) -> str:
    """对眼科眼底图像进行AI疾病诊断。

    接收眼底图像（优先使用后端签发的短期图片引用），返回疾病预测结果，
    包括预测标签、概率、top-1 与 top-2 的实测概率差、疾病描述和Top3预测。

    Args:
        image_input: 图片输入，支持以下格式：
            - 后端签发的短期图片引用（首选）: iris-img://<payload>?exp=...&sig=...
            - 本地文件路径: /path/to/image.jpg 或 C:\\path\\to\\image.png
              （仅限 MCP_IMAGE_ROOT 之内的目录）
            - Base64编码字符串: iVBORw0KGgo...
            - HTTP/HTTPS URL: 仅当服务显式开启 MCP_ALLOW_REMOTE_URLS 时接受

    Returns:
        JSON格式的诊断结果，包含 predicted_label、predicted_label_cn、probability、
        top2_margin（top-1 与 top-2 的概率差，实测值）、description 和
        top_predictions 字段。不提供「可靠性」评分：模型自评与可靠性是两回事。
    """
    return await diagnose(image_input)


def _serve(config: MCPConfig) -> None:
    """按配置启动 HTTP 传输。

    - 只绑回环：直接交给 SDK 启动。
    - 对外绑定：必须带 `MCP_AUTH_TOKEN`（配置层已强制），这里再把它装成
      逐请求 Bearer 校验的中间件。SDK 不提供可注入的 app 时直接失败，
      不退化成「没装鉴权也照跑」。
    """
    if not config.auth_token:
        mcp.run(transport="streamable-http", host=config.host, port=config.port)
        return
    if not hasattr(mcp, "streamable_http_app"):
        raise RuntimeError(
            "当前 MCP SDK 无法注入鉴权中间件：请使用支持 streamable_http_app() 的版本，"
            "或不要设置 MCP_AUTH_TOKEN 并把 MCP_HOST 保持为 127.0.0.1"
        )
    import uvicorn

    app = mcp.streamable_http_app()
    app.add_middleware(BearerTokenMiddleware, token=config.auth_token)
    uvicorn.run(app, host=config.host, port=config.port, log_level=config.log_level.lower())


def main():
    config = MCPConfig.from_env()

    logging.basicConfig(
        level=getattr(logging, config.log_level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    logger.info(f"Starting MCP server on {config.host}:{config.port} "
                f"(auth={'bearer' if config.auth_token else 'none'}, "
                f"image_root={config.image_root or 'disabled'}, "
                f"image_ref={'configured' if reference_secret_configured() else 'disabled'}, "
                f"remote_urls={'allowed' if remote_urls_allowed() else 'refused'})")
    load_model(config)
    logger.info("Model loaded, server ready")

    _serve(config)


if __name__ == "__main__":
    main()
