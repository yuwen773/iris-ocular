import re
from collections.abc import AsyncIterator

from pydantic import ValidationError

from iris_news_worker.adapters.fields import (
    MAX_PAGES,
    PAGE_SIZE,
    date_range,
    day_field,
    list_field,
    object_field,
    required_text,
    text_field,
)
from iris_news_worker.contracts import (
    MAX_WINDOW_RECORDS,
    CollectionWindow,
    FailureCode,
    Material,
    Source,
)
from iris_news_worker.errors import WorkerError
from iris_news_worker.transport import SourceHttp

HOST = "www.ebi.ac.uk"
LICENSES = {
    "CC0": "CC0",
    "CC0 1.0": "CC0",
    "CC-BY": "CC-BY",
    "CC BY": "CC-BY",
    "CC BY 4.0": "CC-BY",
}


def _record(data: dict) -> Material:
    collection = required_text(data.get("source"))
    identifier = required_text(data.get("id"))
    if not re.fullmatch(r"[A-Z][A-Z0-9_]{0,15}", collection) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9_.-]{0,110}", identifier
    ):
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    license_name = text_field(data.get("license"))
    license_code = LICENSES.get(license_name.upper()) if license_name is not None else None
    abstract = text_field(data.get("abstractText")) if license_code is not None else None
    return Material(
        source=Source.EUROPE_PMC,
        source_record_id=f"{collection}:{identifier}",
        url=f"https://europepmc.org/article/{collection}/{identifier}",
        title=required_text(data.get("title")),
        language=text_field(data.get("language")),
        published_on=text_field(data.get("firstPublicationDate"))
        or text_field(data.get("pubYear")),
        updated_on=day_field(data.get("dateOfRevision") or data.get("updateDate")),
        pmid=text_field(data.get("pmid")) or (identifier if collection == "MED" else None),
        doi=text_field(data.get("doi")),
        abstract=abstract,
        abstract_license=license_code if abstract is not None else None,
    )


class EuropePmc:
    def __init__(self, http: SourceHttp):
        self._http = http

    async def pages(self, window: CollectionWindow) -> AsyncIterator[list[Material]]:
        start, end = date_range(window)
        query = f"({window.query}) AND UPDATE_DATE:[{start} TO {end}]"
        cursor = "*"
        used_cursors = {cursor}
        count = 0
        for _ in range(MAX_PAGES):
            data = await self._http.get_json(
                HOST,
                "/europepmc/webservices/rest/search",
                {
                    "query": query,
                    "format": "json",
                    "resultType": "core",
                    "pageSize": PAGE_SIZE,
                    "cursorMark": cursor,
                },
            )
            hits = data.get("hitCount")
            if type(hits) is not int or hits < 0 or data.get("partialResults") is True:
                raise WorkerError(FailureCode.BAD_SOURCE_DATA)
            if hits > MAX_WINDOW_RECORDS:
                raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
            results = list_field(object_field(data.get("resultList")).get("result"))
            next_cursor = text_field(data.get("nextCursorMark"))
            if not results and count >= hits and next_cursor in {None, cursor}:
                return
            if next_cursor is None or next_cursor in used_cursors or not results:
                raise WorkerError(FailureCode.BAD_SOURCE_DATA)
            count += len(results)
            if count > MAX_WINDOW_RECORDS:
                raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
            try:
                yield [_record(object_field(item)) for item in results]
            except ValidationError:
                raise WorkerError(FailureCode.BAD_SOURCE_DATA) from None
            cursor = next_cursor
            used_cursors.add(cursor)
        raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
