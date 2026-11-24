"""Strict projections from upstream documents into News Material fields."""

from datetime import date, timedelta

from iris_news_worker.contracts import CollectionWindow, FailureCode
from iris_news_worker.errors import WorkerError

PAGE_SIZE = 50
MAX_PAGES = 200


def date_range(window: CollectionWindow) -> tuple[str, str]:
    return window.start_on.isoformat(), (window.end_before - timedelta(days=1)).isoformat()


def object_field(value: object) -> dict:
    if not isinstance(value, dict):
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    return value


def list_field(value: object) -> list:
    if not isinstance(value, list):
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    return value


def text_field(value: object) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    return value.strip() or None


def required_text(value: object) -> str:
    result = text_field(value)
    if result is None:
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    return result


def day_field(value: object) -> date | None:
    result = text_field(value)
    if result is None:
        return None
    try:
        day = date.fromisoformat(result)
    except ValueError:
        raise WorkerError(FailureCode.BAD_SOURCE_DATA) from None
    if day.isoformat() != result:
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    return day
