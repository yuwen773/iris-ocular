"""Bounded HTTP requests and public-source egress policy."""

import asyncio
import ipaddress
import json
import socket
import threading
from collections.abc import Awaitable, Callable, Mapping
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from http.cookiejar import CookieJar, DefaultCookiePolicy

import httpx
from tenacity import AsyncRetrying, RetryCallState, retry_if_exception_type, stop_after_attempt

from iris_news_worker.contracts import FailureCode
from iris_news_worker.errors import WorkerError

MAX_SOURCE_BYTES = 4 * 1024 * 1024
MAX_CONTROL_BYTES = 64 * 1024
SOURCE_INTERVALS = {
    "eutils.ncbi.nlm.nih.gov": 0.35,
    "www.ebi.ac.uk": 1.0,
    "clinicaltrials.gov": 1.0,
}
REDIRECTS = {301, 302, 303, 307, 308}
Resolver = Callable[[str], Awaitable[tuple[str, ...]]]
_DNS_SLOTS = threading.BoundedSemaphore(4)


async def resolve(host: str) -> tuple[str, ...]:
    try:
        return (str(ipaddress.ip_address(host)),)
    except ValueError:
        pass
    if not _DNS_SLOTS.acquire(blocking=False):
        raise OSError("DNS resolver capacity exhausted")
    loop = asyncio.get_running_loop()
    result = loop.create_future()

    def finish(addresses: tuple[str, ...], error: OSError | None) -> None:
        if not result.done():
            if error is None:
                result.set_result(addresses)
            else:
                result.set_exception(error)

    def lookup() -> None:
        addresses = ()
        error = None
        try:
            answers = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
            addresses = tuple(dict.fromkeys(answer[4][0] for answer in answers))
        except OSError as exc:
            error = exc
        finally:
            _DNS_SLOTS.release()
        try:
            loop.call_soon_threadsafe(finish, addresses, error)
        except RuntimeError:
            # A cancelled DNS lookup may finish after the CLI closes its event loop.
            pass

    # A blocking OS resolver must not delay process exit after the task deadline.
    try:
        threading.Thread(target=lookup, name="iris-news-dns", daemon=True).start()
    except RuntimeError:
        _DNS_SLOTS.release()
        raise OSError("DNS resolver could not start") from None
    return await result


class TransientHttpError(WorkerError):
    def __init__(self, code: FailureCode, retry_after: float = 0.0):
        super().__init__(code, retryable=True)
        self.retry_after = retry_after


class _RejectCookies(DefaultCookiePolicy):
    def set_ok(self, cookie, request) -> bool:
        return False

    def return_ok(self, cookie, request) -> bool:
        return False


def _retry_delay(state: RetryCallState) -> float:
    error = state.outcome.exception() if state.outcome is not None else None
    delay = 0.25 * 2 ** (state.attempt_number - 1)
    return max(delay, error.retry_after) if isinstance(error, TransientHttpError) else delay


def _retry_after(value: str | None) -> float:
    if value is None:
        return 0.0
    try:
        if value.isascii() and value.isdecimal():
            return float(int(value))
        return max(0.0, (parsedate_to_datetime(value) - datetime.now(UTC)).total_seconds())
    except (ValueError, OverflowError, TypeError):
        return 0.0


def new_http_client(max_concurrency: int, *, source: bool) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        follow_redirects=False,
        trust_env=False,
        timeout=httpx.Timeout(8.0, connect=4.0),
        limits=httpx.Limits(
            max_connections=max_concurrency, max_keepalive_connections=0 if source else 4
        ),
        cookies=CookieJar(policy=_RejectCookies()),
        headers={"Accept-Encoding": "identity", "User-Agent": "IrisNewsWorker/0.1"},
    )


async def read_body(response: httpx.Response, limit: int) -> bytes:
    encoding = response.headers.get("content-encoding", "identity").strip().lower()
    length = response.headers.get("content-length")
    if encoding != "identity":
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    if length is not None:
        try:
            declared_size = int(length)
        except ValueError:
            raise WorkerError(FailureCode.BAD_SOURCE_DATA) from None
        if declared_size < 0:
            raise WorkerError(FailureCode.BAD_SOURCE_DATA)
        if declared_size > limit:
            raise WorkerError(FailureCode.RESPONSE_TOO_LARGE)
    # Mock transports can provide an already consumed response.
    if response.is_stream_consumed:
        if len(response.content) > limit:
            raise WorkerError(FailureCode.RESPONSE_TOO_LARGE)
        return response.content
    data = bytearray()
    async for chunk in response.aiter_raw():
        if len(data) + len(chunk) > limit:
            raise WorkerError(FailureCode.RESPONSE_TOO_LARGE)
        data.extend(chunk)
    return bytes(data)


async def retry_request[T](request: Callable[[], Awaitable[T]], attempts: int = 3) -> T:
    retry = AsyncRetrying(
        stop=stop_after_attempt(attempts),
        retry=retry_if_exception_type(TransientHttpError),
        wait=_retry_delay,
        reraise=True,
    )
    return await retry(request)


def raise_for_transient(response: httpx.Response, code: FailureCode) -> None:
    if response.status_code == 429 or 500 <= response.status_code < 600:
        raise TransientHttpError(code, _retry_after(response.headers.get("retry-after")))


def _is_public(address: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    # Reject IPv6 transition/scoped forms in addition to non-public addresses.
    if isinstance(address, ipaddress.IPv6Address) and (
        address.ipv4_mapped is not None
        or address.sixtofour is not None
        or address.teredo is not None
        or address.scope_id is not None
        or address in ipaddress.IPv6Network("64:ff9b::/96")
        or address in ipaddress.IPv6Network("64:ff9b:1::/48")
    ):
        return False
    return (
        address.is_global
        and not address.is_multicast
        and not address.is_reserved
        and not address.is_site_local
        if isinstance(address, ipaddress.IPv6Address)
        else address.is_global and not address.is_multicast and not address.is_reserved
    )


class SourceHttp:
    def __init__(
        self,
        client: httpx.AsyncClient,
        requests: asyncio.Semaphore,
        *,
        resolver: Resolver = resolve,
        intervals: Mapping[str, float] = SOURCE_INTERVALS,
    ):
        self._client = client
        self._requests = requests
        self._resolve = resolver
        self._intervals = intervals
        self._locks = {host: asyncio.Lock() for host in SOURCE_INTERVALS}
        self._next_request: dict[str, float] = {}
        self._throttled_until: dict[str, float] = {}

    async def _get(self, url: httpx.URL, allowed_host: str) -> bytes:
        for _ in range(4):
            if (
                url.scheme != "https"
                or url.host != allowed_host
                or allowed_host not in self._locks
                or url.port not in {None, 443}
                or url.userinfo
                or url.fragment
            ):
                raise WorkerError(FailureCode.SOURCE_BLOCKED)

            async with self._locks[allowed_host]:
                loop = asyncio.get_running_loop()
                next_request = max(
                    self._next_request.get(allowed_host, 0.0),
                    self._throttled_until.get(allowed_host, 0.0),
                )
                if (delay := next_request - loop.time()) > 0:
                    await asyncio.sleep(delay)
                try:
                    addresses = [
                        ipaddress.ip_address(value) for value in await self._resolve(url.host)
                    ]
                except OSError:
                    raise TransientHttpError(FailureCode.SOURCE_UNAVAILABLE) from None
                except ValueError:
                    raise WorkerError(FailureCode.SOURCE_BLOCKED) from None
                if not addresses or not all(_is_public(address) for address in addresses):
                    raise WorkerError(FailureCode.SOURCE_BLOCKED)

                # Connect to the validated IP, retaining the origin for Host, SNI and TLS
                # certificate verification. Source connections are never reused.
                pinned_url = url.copy_with(host=str(addresses[0]))
                async with self._requests:
                    self._next_request[allowed_host] = loop.time() + self._intervals[allowed_host]
                    try:
                        async with self._client.stream(
                            "GET",
                            pinned_url,
                            headers={"Host": allowed_host},
                            extensions={"sni_hostname": allowed_host},
                        ) as response:
                            if response.status_code in REDIRECTS:
                                location = response.headers.get("location")
                                if not location:
                                    raise WorkerError(FailureCode.SOURCE_BLOCKED)
                                try:
                                    url = url.join(location)
                                except (ValueError, httpx.InvalidURL):
                                    raise WorkerError(FailureCode.SOURCE_BLOCKED) from None
                                continue
                            try:
                                raise_for_transient(response, FailureCode.SOURCE_UNAVAILABLE)
                            except TransientHttpError as error:
                                self._throttled_until[allowed_host] = (
                                    loop.time() + error.retry_after
                                )
                                raise
                            if response.status_code != 200:
                                raise WorkerError(FailureCode.SOURCE_REJECTED)
                            return await read_body(response, MAX_SOURCE_BYTES)
                    except httpx.TransportError:
                        raise TransientHttpError(FailureCode.SOURCE_UNAVAILABLE) from None
        raise WorkerError(FailureCode.SOURCE_BLOCKED)

    async def get(self, host: str, path: str, params: Mapping[str, str | int]) -> bytes:
        url = httpx.URL(scheme="https", host=host, path=path, params=params)

        async def send() -> bytes:
            return await self._get(url, host)

        return await retry_request(send)

    async def get_json(self, host: str, path: str, params: Mapping[str, str | int]) -> dict:
        data = await self.get(host, path, params)
        try:
            result = json.loads(data)
        except (ValueError, RecursionError):
            raise WorkerError(FailureCode.BAD_SOURCE_DATA) from None
        if not isinstance(result, dict):
            raise WorkerError(FailureCode.BAD_SOURCE_DATA)
        return result
