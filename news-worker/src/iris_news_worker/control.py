"""Machine-authenticated News Engine control-plane client."""

import asyncio

import httpx
from pydantic import ValidationError

from iris_news_worker.contracts import (
    MAX_REQUEST_BYTES,
    Batch,
    BatchReceipt,
    Complete,
    CompleteReceipt,
    Fail,
    FailureCode,
    FailureReceipt,
    Lease,
    WireModel,
)
from iris_news_worker.errors import WorkerError
from iris_news_worker.transport import (
    MAX_CONTROL_BYTES,
    TransientHttpError,
    raise_for_transient,
    read_body,
    resolve,
    retry_request,
)

CONTROL_PREFIX = "/api/internal/news/v1/leases"
WORKER_KEY_HEADER = "X-Iris-News-Worker-Key"


class ControlClient:
    def __init__(
        self,
        client: httpx.AsyncClient,
        base: str,
        key: str,
        requests: asyncio.Semaphore,
    ):
        url = httpx.URL(base)
        private_mock = url.scheme == "http" and url.host in {"127.0.0.1", "::1"}
        if (
            (url.scheme != "https" and not private_mock)
            or not url.host
            or url.userinfo
            or url.path not in {"", "/"}
            or url.query
            or url.fragment
        ):
            raise ValueError("Invalid control-plane origin")
        if (
            not 16 <= len(key) <= 256
            or not key.isascii()
            or not all(33 <= ord(c) <= 126 for c in key)
        ):
            raise ValueError("Invalid control-plane credential")
        self._client = client
        self._url = url
        self._headers = {WORKER_KEY_HEADER: key, "Accept": "application/json"}
        self._requests = requests

    async def _post[T: WireModel](
        self,
        path: str,
        output: type[T],
        *,
        body: WireModel | None = None,
        key: str | None = None,
    ) -> T | None:
        data = None if body is None else body.wire_bytes()
        headers = dict(self._headers)
        if key is not None:
            headers["Idempotency-Key"] = key
            headers["Content-Type"] = "application/json"
        if data is not None and len(data) > MAX_REQUEST_BYTES:
            raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
        url = self._url.copy_with(path=CONTROL_PREFIX + path)

        async def send() -> T | None:
            async with self._requests:
                try:
                    addresses = await resolve(url.host)
                    if not addresses:
                        raise OSError("No control-plane address")
                    destination = url.copy_with(host=addresses[0])
                    async with self._client.stream(
                        "POST",
                        destination,
                        content=data,
                        headers={**headers, "Host": url.netloc.decode("ascii")},
                        extensions={"sni_hostname": url.host},
                    ) as response:
                        raise_for_transient(response, FailureCode.CONTROL_UNAVAILABLE)
                        if response.status_code == 204 and body is None:
                            return None
                        if response.status_code != 200:
                            raise WorkerError(FailureCode.CONTROL_REJECTED)
                        try:
                            payload = await read_body(response, MAX_CONTROL_BYTES)
                            return output.model_validate_json(payload, by_name=False)
                        except (ValidationError, WorkerError):
                            raise WorkerError(FailureCode.CONTROL_REJECTED) from None
                except (httpx.TransportError, OSError):
                    raise TransientHttpError(FailureCode.CONTROL_UNAVAILABLE) from None

        # Lease allocation has no replay key. A lost claim response expires as a lease.
        return await send() if key is None else await retry_request(send)

    async def claim(self) -> Lease | None:
        return await self._post("/claim", Lease)

    async def submit(self, task_id: str, batch: Batch) -> BatchReceipt:
        receipt = await self._post(
            f"/{task_id}/batches",
            BatchReceipt,
            body=batch,
            key=f"{batch.generation}:{batch.sequence}",
        )
        if (
            receipt is None
            or receipt.sequence != batch.sequence
            or receipt.record_count != len(batch.records)
        ):
            raise WorkerError(FailureCode.CONTROL_REJECTED)
        return receipt

    async def complete(self, task_id: str, completion: Complete) -> CompleteReceipt:
        receipt = await self._post(
            f"/{task_id}/complete",
            CompleteReceipt,
            body=completion,
            key=f"{completion.generation}:complete",
        )
        if receipt is None or receipt.committed_cursor != completion.next_cursor:
            raise WorkerError(FailureCode.CONTROL_REJECTED)
        return receipt

    async def fail(self, task_id: str, failure: Fail) -> FailureReceipt:
        receipt = await self._post(
            f"/{task_id}/fail", FailureReceipt, body=failure, key=f"{failure.generation}:fail"
        )
        if receipt is None:
            raise WorkerError(FailureCode.CONTROL_REJECTED)
        return receipt
