"""Bearer 中间件的单元测试：不依赖 mcp / Starlette，直接驱动 ASGI 三元组。"""

import pytest

from mcp_server.auth import BearerTokenMiddleware


def _scope(headers=None, kind="http"):
    return {
        "type": kind,
        "headers": [(name.encode("latin-1"), value.encode("latin-1")) for name, value in (headers or [])],
    }


async def _run(scope, token="s3cret"):
    """把中间件套在一个记录「是否被调用」的假 app 上。"""
    sent = []
    reached = {"app": False}

    async def app(inner_scope, receive, send):
        reached["app"] = True

    async def send(message):
        sent.append(message)

    async def receive():
        return {"type": "http.request"}

    await BearerTokenMiddleware(app=app, token=token)(scope, receive, send)
    return reached["app"], sent


@pytest.mark.asyncio
async def test_missing_token_is_rejected_with_401():
    reached, sent = await _run(_scope())
    assert reached is False
    assert sent[0]["status"] == 401
    assert sent[-1]["body"].startswith(b'{"error":"unauthorized"')


@pytest.mark.asyncio
async def test_wrong_token_is_rejected():
    reached, sent = await _run(_scope([("authorization", "Bearer other")]))
    assert reached is False
    assert sent[0]["status"] == 401


@pytest.mark.asyncio
async def test_correct_token_passes_through():
    reached, sent = await _run(_scope([("Authorization", "Bearer s3cret")]))
    assert reached is True
    assert sent == []


@pytest.mark.asyncio
async def test_lifespan_messages_are_not_blocked():
    reached, sent = await _run(_scope(kind="lifespan"))
    assert reached is True
    assert sent == []


def test_empty_token_is_refused_at_construction():
    with pytest.raises(ValueError):
        BearerTokenMiddleware(app=None, token="")
