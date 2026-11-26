import asyncio
import subprocess
import sys

import httpx
import pytest
import respx

from iris_news_worker import cli
from iris_news_worker.contracts import FailureCode, Source
from iris_news_worker.transport import (
    MAX_SOURCE_BYTES,
    SOURCE_INTERVALS,
    SourceHttp,
    new_http_client,
)
from tests.mock_control import MockControl
from tests.support import (
    CLINICAL_TRIALS_HOST,
    TRIALS,
    configure_cli,
    public_dns,
    trial_page,
)


@respx.mock
def test_rate_limit_server_failure_and_read_timeout_are_retried(monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    route = respx.get(url__startswith=TRIALS).mock(
        side_effect=[
            httpx.Response(429, headers={"Retry-After": "0"}),
            httpx.Response(502),
            trial_page(),
        ]
    )
    assert cli.main(["run-once", "--concurrency", "1"]) == 0
    assert route.call_count == 3
    next_mock = MockControl()
    configure_cli(monkeypatch, next_mock)
    route.mock(side_effect=httpx.ReadTimeout)
    assert cli.main(["run-once", "--concurrency", "1"]) == 1
    assert next_mock.failures[0].code == FailureCode.SOURCE_UNAVAILABLE
    assert next_mock.failures[0].retryable
    assert next_mock.complete_cursor is None


@pytest.mark.parametrize(
    "target",
    [
        "http://127.0.0.1/private",
        "https://169.254.169.254/private",
        "https://clinicaltrials.gov:443@127.0.0.1/private",
        "https://clinicaltrials.gov:8443/private",
    ],
)
@respx.mock
def test_rejects_redirect_targets_without_sending_to_them(target, monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    route = respx.get(url__startswith=TRIALS).mock(
        return_value=httpx.Response(302, headers={"Location": target})
    )
    assert cli.main(["run-once", "--concurrency", "1"]) == 1
    assert route.call_count == 1
    assert mock.failures[0].code == FailureCode.SOURCE_BLOCKED


@respx.mock
def test_re_resolves_allowed_host_on_redirect_and_rejects_private_dns(monkeypatch):
    mock = MockControl()
    answers = iter((("93.184.216.34",), ("10.0.0.1",)))

    async def rebind(_host):
        return next(answers)

    configure_cli(monkeypatch, mock, resolver=rebind)
    route = respx.get(url__startswith=TRIALS).mock(
        return_value=httpx.Response(302, headers={"Location": "/another"})
    )
    assert cli.main(["run-once", "--concurrency", "1"]) == 1
    assert route.call_count == 1
    assert mock.failures[0].code == FailureCode.SOURCE_BLOCKED


class LargeBody(httpx.AsyncByteStream):
    async def __aiter__(self):
        for _ in range(9):
            yield b"a" * (MAX_SOURCE_BYTES // 8)


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(200, headers={"Content-Length": str(MAX_SOURCE_BYTES + 1)}),
        httpx.Response(200, stream=LargeBody()),
    ],
)
@respx.mock
def test_rejects_oversized_response_with_or_without_declared_length(response, monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=TRIALS).mock(return_value=response)
    assert cli.main(["run-once", "--concurrency", "1"]) == 1
    assert mock.failures[0].code == FailureCode.RESPONSE_TOO_LARGE
    assert mock.complete_cursor is None


@respx.mock
def test_pubmed_rejects_xml_entities_and_leaves_cursor(monkeypatch):
    from tests.support import PUBMED_FETCH, PUBMED_SEARCH, pubmed_count

    mock = MockControl(Source.PUBMED)
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=PUBMED_SEARCH).mock(return_value=pubmed_count(1))
    respx.get(url__startswith=PUBMED_FETCH).mock(
        return_value=httpx.Response(
            200,
            text='<!DOCTYPE root [<!ENTITY x "synthetic">]><PubmedArticleSet>&x;</PubmedArticleSet>',
        )
    )
    assert cli.main(["run-once", "--concurrency", "1"]) == 1
    assert mock.failures[0].code == FailureCode.BAD_SOURCE_DATA
    assert mock.complete_cursor is None


@respx.mock
async def test_same_host_never_has_two_simultaneous_requests():
    occupied = 0
    peak = 0

    async def get(_request):
        nonlocal occupied, peak
        occupied += 1
        peak = max(peak, occupied)
        await asyncio.sleep(0.01)
        occupied -= 1
        return trial_page()

    respx.get(url__startswith=TRIALS).mock(side_effect=get)
    async with new_http_client(4, source=True) as client:
        http = SourceHttp(
            client,
            asyncio.Semaphore(4),
            resolver=public_dns,
            intervals=dict.fromkeys(SOURCE_INTERVALS, 0),
        )
        await asyncio.gather(
            *(http.get(CLINICAL_TRIALS_HOST, "/api/v2/studies", {}) for _ in range(4))
        )
    assert peak == 1


@respx.mock
async def test_sources_sharing_an_ip_do_not_send_each_others_cookies():
    route = respx.get(url__startswith=TRIALS).mock(
        return_value=httpx.Response(200, headers={"Set-Cookie": "source=fixture; Path=/"})
    )
    async with new_http_client(4, source=True) as client:
        http = SourceHttp(
            client,
            asyncio.Semaphore(4),
            resolver=public_dns,
            intervals=dict.fromkeys(SOURCE_INTERVALS, 0),
        )
        await http.get(CLINICAL_TRIALS_HOST, "/api/v2/studies", {})
        await http.get("www.ebi.ac.uk", "/api/v2/studies", {})
    first, second = (call.request for call in route.calls)
    assert first.headers["Host"] != second.headers["Host"]
    assert "Cookie" not in second.headers


def test_blocked_os_dns_does_not_keep_worker_process_alive():
    source = """
import asyncio
import socket
import time
from iris_news_worker.transport import resolve

socket.getaddrinfo = lambda *args, **kwargs: time.sleep(20)
try:
    asyncio.run(asyncio.wait_for(resolve("fixture.invalid"), timeout=0.05))
except TimeoutError:
    print("exited at deadline")
"""
    process = subprocess.run(
        [sys.executable, "-c", source], capture_output=True, text=True, timeout=5, check=True
    )
    assert process.stdout.strip() == "exited at deadline"
