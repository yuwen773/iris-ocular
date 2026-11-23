"""MCP HTTP 传输层的访问控制。

流式 HTTP 传输本身不带鉴权：谁能连上端口，谁就能调用诊断工具。默认只绑回环地址，
对外绑定必须配 `MCP_AUTH_TOKEN`，由这里的中间件逐请求校验。

用纯 ASGI 实现（不依赖 Starlette），因为 `FastMCP` 在不同版本里给出的 app 类型不同，
中间件只需要 (scope, receive, send) 这三个东西。
"""

from __future__ import annotations

import hmac

_UNAUTHORIZED_BODY = b'{"error":"unauthorized","detail":"missing or invalid bearer token"}'


class BearerTokenMiddleware:
    """拒绝所有未携带正确 `Authorization: Bearer <token>` 的 HTTP 请求。"""

    def __init__(self, app, token: str):
        if not token:
            raise ValueError("token 不能为空：没有令牌的鉴权中间件等于没有鉴权")
        self.app = app
        self._expected = f"Bearer {token}".encode("latin-1")

    async def __call__(self, scope, receive, send):
        # 只拦 HTTP 请求；lifespan 等协议消息原样放行。
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        presented = b""
        for name, value in scope.get("headers") or ():
            if name.lower() == b"authorization":
                presented = value
                break
        # 定长比较，避免用响应时间泄露令牌前缀。
        if not hmac.compare_digest(presented, self._expected):
            await send({
                "type": "http.response.start",
                "status": 401,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"www-authenticate", b"Bearer"),
                    (b"cache-control", b"no-store"),
                ],
            })
            await send({"type": "http.response.body", "body": _UNAUTHORIZED_BODY})
            return
        await self.app(scope, receive, send)
