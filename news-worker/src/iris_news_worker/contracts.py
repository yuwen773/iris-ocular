"""Worker bindings for the versioned News Engine wire protocol."""

import hashlib
import json
from datetime import date
from enum import StrEnum
from typing import Annotated, Literal, Self
from urllib.parse import urlsplit

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

MAX_BATCH_RECORDS = 50
MAX_REQUEST_BYTES = 2 * 1024 * 1024
MAX_WINDOW_RECORDS = 10_000
MAX_WINDOW_BATCHES = MAX_WINDOW_RECORDS

Identifier = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")]
Token = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{16,256}$")]
Text = Annotated[str, StringConstraints(min_length=1, max_length=2048)]
DateText = Annotated[str, StringConstraints(pattern=r"^[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?$")]
PositiveInteger = Annotated[int, Field(gt=0, le=9_007_199_254_740_991)]
BatchSequence = Annotated[int, Field(ge=1, le=MAX_WINDOW_BATCHES)]
RecordCount = Annotated[int, Field(ge=0, le=MAX_WINDOW_RECORDS)]
BatchCount = Annotated[int, Field(ge=0, le=MAX_WINDOW_BATCHES)]


class Source(StrEnum):
    PUBMED = "pubmed"
    EUROPE_PMC = "europe-pmc"
    CLINICAL_TRIALS = "clinicaltrials-gov"


class FailureCode(StrEnum):
    BAD_SOURCE_DATA = "BAD_SOURCE_DATA"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    INTERRUPTED = "INTERRUPTED"
    CONTROL_UNAVAILABLE = "CONTROL_UNAVAILABLE"
    CONTROL_REJECTED = "CONTROL_REJECTED"
    SOURCE_BLOCKED = "SOURCE_BLOCKED"
    SOURCE_REJECTED = "SOURCE_REJECTED"
    SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
    RESPONSE_TOO_LARGE = "RESPONSE_TOO_LARGE"
    WINDOW_TOO_LARGE = "WINDOW_TOO_LARGE"
    INTERNAL = "INTERNAL"


class WireModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, strict=True, extra="forbid", validate_by_name=True
    )

    def wire_bytes(self) -> bytes:
        return self.model_dump_json(by_alias=True, exclude_none=True).encode("utf-8")


class CollectionWindow(WireModel):
    source: Source
    source_config_version: PositiveInteger
    query: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1024)]
    start_on: date
    end_before: date

    @model_validator(mode="after")
    def check_window(self) -> Self:
        if self.start_on >= self.end_before:
            raise ValueError("Window must have a positive date range")
        return self


class Lease(WireModel):
    task_id: Identifier
    generation: PositiveInteger
    lease_token: Token
    lease_expires_at: AwareDatetime
    window: CollectionWindow


class Attempt(WireModel):
    generation: PositiveInteger
    lease_token: Token


class TrialDetails(WireModel):
    study_type: Identifier | None = None
    overall_status: Identifier | None = None
    phases: Annotated[list[Identifier], Field(max_length=16)] | None = None
    conditions: Annotated[list[Text], Field(max_length=100)] | None = None


class Material(WireModel):
    source: Source
    source_record_id: Identifier
    url: Annotated[str, StringConstraints(pattern=r"^https://", max_length=512)]
    title: Text
    language: Annotated[str, StringConstraints(min_length=1, max_length=80)] | None = None
    published_on: DateText | None = None
    updated_on: date | None = None
    pmid: Annotated[str, StringConstraints(pattern=r"^[1-9][0-9]{0,15}$")] | None = None
    doi: Annotated[str, StringConstraints(min_length=1, max_length=512)] | None = None
    abstract: Annotated[str, StringConstraints(min_length=1, max_length=12_000)] | None = None
    abstract_license: Literal["CC0", "CC-BY"] | None = None
    trial: TrialDetails | None = None

    @field_validator("title", "language", "doi", "abstract")
    @classmethod
    def require_content(cls, value: str | None) -> str | None:
        if value is not None and not value.strip():
            raise ValueError("Expected non-blank source text")
        return value

    @field_validator("url")
    @classmethod
    def check_url(cls, value: str) -> str:
        url = urlsplit(value)
        if not url.hostname or url.username is not None or url.password is not None:
            raise ValueError("Expected a source link without credentials")
        return value

    @field_validator("published_on")
    @classmethod
    def check_partial_date(cls, value: str | None) -> str | None:
        if value is not None:
            date.fromisoformat(value + {4: "-01-01", 7: "-01", 10: ""}[len(value)])
        return value

    @model_validator(mode="after")
    def check_abstract_license(self) -> Self:
        if (self.abstract is None) != (self.abstract_license is None):
            raise ValueError("Abstract and license must be present together")
        return self

    def content_digest(self) -> str:
        payload = self.model_dump(mode="json", by_alias=True, exclude_none=True)
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class Batch(Attempt):
    sequence: BatchSequence
    records: Annotated[list[Material], Field(min_length=1, max_length=MAX_BATCH_RECORDS)]


class BatchReceipt(WireModel):
    sequence: BatchSequence
    record_count: Annotated[int, Field(ge=1, le=MAX_BATCH_RECORDS)]


class Complete(Attempt):
    batch_count: BatchCount
    record_count: RecordCount
    next_cursor: date

    @model_validator(mode="after")
    def check_counts(self) -> Self:
        if not self.batch_count <= self.record_count <= self.batch_count * MAX_BATCH_RECORDS:
            raise ValueError("Record count is inconsistent with batch count")
        return self


class CompleteReceipt(WireModel):
    committed_cursor: date


class Fail(Attempt):
    code: FailureCode
    retryable: bool


class FailureReceipt(WireModel):
    status: Literal["FAILED", "RETRY_SCHEDULED"]


class ControlError(WireModel):
    code: Literal[
        "INVALID_REQUEST",
        "UNAUTHENTICATED",
        "FORBIDDEN",
        "UNKNOWN_TASK",
        "STALE_LEASE",
        "IDEMPOTENCY_CONFLICT",
        "INCOMPLETE_BATCHES",
        "UNAVAILABLE",
    ]
