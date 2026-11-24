"""Single-invocation task execution with lease fencing and bounded shutdown."""

import asyncio
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import NamedTuple

from iris_news_worker.adapters import SourceAdapter
from iris_news_worker.contracts import (
    MAX_BATCH_RECORDS,
    MAX_WINDOW_RECORDS,
    Batch,
    Complete,
    Fail,
    FailureCode,
    Lease,
    Source,
)
from iris_news_worker.control import ControlClient
from iris_news_worker.errors import WorkerError

MAX_CONCURRENCY = 4
MAX_RUN_SECONDS = 600
SHUTDOWN_GRACE_SECONDS = 5


class TaskResult(NamedTuple):
    status: str
    code: FailureCode | None = None
    reported: bool = False


class Worker:
    def __init__(self, control: ControlClient, adapters: Mapping[Source, SourceAdapter]):
        self._control = control
        self._adapters = adapters

    async def _collect(self, lease: Lease) -> Complete:
        generation = lease.generation
        token = lease.lease_token
        seen: set[tuple[str, str]] = set()
        sequence = 0
        record_count = 0
        observed = 0
        async for page in self._adapters[lease.window.source].pages(lease.window):
            observed += len(page)
            if observed > MAX_WINDOW_RECORDS:
                raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
            records = []
            for record in page:
                if record.source != lease.window.source:
                    raise WorkerError(FailureCode.BAD_SOURCE_DATA)
                identity = (record.source_record_id, record.content_digest())
                if identity not in seen:
                    seen.add(identity)
                    records.append(record)
            for start in range(0, len(records), MAX_BATCH_RECORDS):
                sequence += 1
                batch = Batch(
                    generation=generation,
                    lease_token=token,
                    sequence=sequence,
                    records=records[start : start + MAX_BATCH_RECORDS],
                )
                receipt = await self._control.submit(lease.task_id, batch)
                record_count += receipt.record_count
        return Complete(
            generation=generation,
            lease_token=token,
            batch_count=sequence,
            record_count=record_count,
            next_cursor=lease.window.end_before,
        )

    async def _failed(self, lease: Lease, error: WorkerError, deadline: float) -> TaskResult:
        reported = False
        if asyncio.get_running_loop().time() < deadline:
            try:
                async with asyncio.timeout_at(deadline):
                    await self._control.fail(
                        lease.task_id,
                        Fail(
                            generation=lease.generation,
                            lease_token=lease.lease_token,
                            code=error.code,
                            retryable=error.retryable,
                        ),
                    )
                reported = True
            except (WorkerError, TimeoutError):
                pass
        return TaskResult("FAILED", error.code, reported)

    async def _execute(self, lease: Lease, run_deadline: float) -> TaskResult:
        loop = asyncio.get_running_loop()
        remaining = (lease.lease_expires_at - datetime.now(UTC)).total_seconds()
        deadline = min(loop.time() + remaining, run_deadline)
        reserve = min(5.0, max(0.0, (deadline - loop.time()) / 10))
        try:
            async with asyncio.timeout_at(deadline - reserve):
                if deadline - reserve <= loop.time():
                    raise TimeoutError
                completion = await self._collect(lease)
        except TimeoutError:
            error = WorkerError(FailureCode.DEADLINE_EXCEEDED, retryable=True)
            return await self._failed(lease, error, deadline)
        except WorkerError as error:
            return await self._failed(lease, error, deadline)
        except Exception:
            return await self._failed(lease, WorkerError(FailureCode.INTERNAL), deadline)
        try:
            async with asyncio.timeout_at(deadline):
                await self._control.complete(lease.task_id, completion)
            return TaskResult("COMPLETED")
        except WorkerError as error:
            return TaskResult("FAILED", error.code)
        except TimeoutError:
            # The final write may have committed even if its response was lost.
            return TaskResult("FAILED", FailureCode.DEADLINE_EXCEEDED)

    async def _slot(self, deadline: float, stopping: asyncio.Event) -> TaskResult:
        if stopping.is_set():
            return TaskResult("IDLE")
        try:
            async with asyncio.timeout_at(deadline):
                lease = await self._control.claim()
            if lease is None:
                return TaskResult("IDLE")
            return await self._execute(lease, deadline)
        except WorkerError as error:
            return TaskResult("FAILED", error.code)
        except TimeoutError:
            return TaskResult("FAILED", FailureCode.DEADLINE_EXCEEDED)
        except asyncio.CancelledError:
            return TaskResult("FAILED", FailureCode.INTERRUPTED)
        except Exception:
            return TaskResult("FAILED", FailureCode.INTERNAL)

    async def run_once(
        self, *, concurrency: int, seconds: float, stopping: asyncio.Event
    ) -> list[TaskResult]:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + seconds
        slots = [asyncio.create_task(self._slot(deadline, stopping)) for _ in range(concurrency)]
        jobs = asyncio.gather(*slots)
        stop_signal = asyncio.create_task(stopping.wait())
        try:
            done, _ = await asyncio.wait((jobs, stop_signal), return_when=asyncio.FIRST_COMPLETED)
            if jobs not in done:
                grace = max(0.0, min(SHUTDOWN_GRACE_SECONDS, deadline - loop.time()))
                if not (await asyncio.wait((jobs,), timeout=grace))[0]:
                    for slot in slots:
                        slot.cancel()
            return await jobs
        finally:
            stop_signal.cancel()
            await asyncio.gather(stop_signal, return_exceptions=True)
