"""Offline control-plane simulator for transport and recovery tests."""

from collections import deque
from datetime import UTC, date, datetime, timedelta

import httpx
from pydantic import ValidationError

from iris_news_worker.contracts import (
    Batch,
    CollectionWindow,
    Complete,
    Fail,
    Lease,
    Source,
)
from iris_news_worker.control import CONTROL_PREFIX, WORKER_KEY_HEADER

KEY = "fixture_worker_key"
BASE = "http://127.0.0.1:8090"


def response(status: int, code: str) -> httpx.Response:
    return httpx.Response(status, json={"code": code})


class MockControl:
    def __init__(self, source: Source = Source.CLINICAL_TRIALS):
        self.lease = Lease(
            task_id="task-001",
            generation=1,
            lease_token="fixture_lease_token_01",
            lease_expires_at=datetime.now(UTC) + timedelta(minutes=2),
            window=CollectionWindow(
                source=source,
                source_config_version=1,
                query="glaucoma",
                start_on=date(2026, 10, 1),
                end_before=date(2026, 10, 15),
            ),
        )
        self.key = KEY
        self.claims = deque([self.lease])
        self.batches: dict[int, Batch] = {}
        self.complete_cursor = None
        self.failures: list[Fail] = []
        self.receipts: dict[tuple, tuple[bytes, dict]] = {}
        self.visible = {}
        self.drop_after_commit: set[str] = set()
        self.calls: list[str] = []
        self.finished = False

    def next_attempt(self) -> None:
        self.lease = self.lease.model_copy(
            update={
                "generation": self.lease.generation + 1,
                "lease_token": "fixture_lease_token_02",
                "lease_expires_at": datetime.now(UTC) + timedelta(minutes=2),
            }
        )
        self.claims.append(self.lease)
        self.batches.clear()
        self.finished = False

    def handle(self, request: httpx.Request) -> httpx.Response:
        if request.method != "POST" or not request.url.path.startswith(CONTROL_PREFIX + "/"):
            return response(404, "UNKNOWN_TASK")
        if request.headers.get(WORKER_KEY_HEADER) != self.key:
            return response(401, "UNAUTHENTICATED")
        path = request.url.path.removeprefix(CONTROL_PREFIX)
        self.calls.append(path)
        if path == "/claim":
            if not self.claims:
                return httpx.Response(204)
            return httpx.Response(200, content=self.claims.popleft().wire_bytes())
        task, _, action = path[1:].partition("/")
        if task != self.lease.task_id or action not in {"batches", "complete", "fail"}:
            return response(404, "UNKNOWN_TASK")
        key = request.headers.get("Idempotency-Key")
        try:
            attempt = {"batches": Batch, "complete": Complete, "fail": Fail}[
                action
            ].model_validate_json(request.content, by_name=False)
        except ValidationError:
            return response(400, "INVALID_REQUEST")
        if isinstance(attempt, Batch):
            expected_key = f"{attempt.generation}:{attempt.sequence}"
        else:
            expected_key = f"{attempt.generation}:{action}"
        if key != expected_key:
            return response(400, "INVALID_REQUEST")
        receipt_id = (task, attempt.generation, action, key)
        if receipt_id in self.receipts:
            previous_body, previous_receipt = self.receipts[receipt_id]
            if previous_body != request.content:
                return response(409, "IDEMPOTENCY_CONFLICT")
            return httpx.Response(200, json=previous_receipt)
        if (
            attempt.generation != self.lease.generation
            or attempt.lease_token != self.lease.lease_token
            or self.lease.lease_expires_at <= datetime.now(UTC)
            or self.finished
        ):
            return response(409, "STALE_LEASE")
        if isinstance(attempt, Batch):
            if any(record.source != self.lease.window.source for record in attempt.records):
                return response(400, "INVALID_REQUEST")
            self.batches[attempt.sequence] = attempt
            receipt = {"sequence": attempt.sequence, "recordCount": len(attempt.records)}
        elif isinstance(attempt, Complete):
            if (
                sorted(self.batches) != list(range(1, attempt.batch_count + 1))
                or sum(len(batch.records) for batch in self.batches.values())
                != attempt.record_count
                or attempt.next_cursor != self.lease.window.end_before
            ):
                return response(409, "INCOMPLETE_BATCHES")
            self.complete_cursor = attempt.next_cursor
            self.visible.update(
                ((record.source, record.source_record_id, record.content_digest()), record)
                for batch in self.batches.values()
                for record in batch.records
            )
            self.finished = True
            receipt = {"committedCursor": attempt.next_cursor.isoformat()}
        else:
            self.failures.append(attempt)
            self.finished = True
            receipt = {"status": "RETRY_SCHEDULED" if attempt.retryable else "FAILED"}
        self.receipts[receipt_id] = (request.content, receipt)
        if action in self.drop_after_commit:
            self.drop_after_commit.remove(action)
            raise httpx.ReadError("fixture dropped response", request=request)
        return httpx.Response(200, json=receipt)
