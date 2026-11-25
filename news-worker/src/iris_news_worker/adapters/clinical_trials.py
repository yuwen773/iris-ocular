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
    MAX_TRIAL_CONDITIONS,
    CollectionWindow,
    FailureCode,
    Material,
    Source,
    TrialDetails,
)
from iris_news_worker.errors import WorkerError
from iris_news_worker.transport import SourceHttp

HOST = "clinicaltrials.gov"
FIELDS = ",".join(
    (
        "NCTId",
        "BriefTitle",
        "StudyFirstPostDate",
        "LastUpdatePostDate",
        "StudyType",
        "OverallStatus",
        "Phase",
        "Condition",
    )
)


def _optional_list(value: object) -> list | None:
    return None if value is None else list_field(value)


def _date_struct(data: dict, field: str) -> str | None:
    value = data.get(field)
    return None if value is None else text_field(object_field(value).get("date"))


def _record(data: dict) -> Material:
    protocol = object_field(data.get("protocolSection"))
    identity = object_field(protocol.get("identificationModule"))
    identifier = required_text(identity.get("nctId"))
    if not re.fullmatch(r"NCT[0-9]{8}", identifier):
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    status = object_field(protocol.get("statusModule", {}))
    design = object_field(protocol.get("designModule", {}))
    conditions = object_field(protocol.get("conditionsModule", {}))
    condition_list = _optional_list(conditions.get("conditions"))
    if condition_list is not None:
        condition_list = condition_list[:MAX_TRIAL_CONDITIONS]
    return Material(
        source=Source.CLINICAL_TRIALS,
        source_record_id=identifier,
        url=f"https://clinicaltrials.gov/study/{identifier}",
        title=required_text(identity.get("briefTitle")),
        published_on=_date_struct(status, "studyFirstPostDateStruct"),
        updated_on=day_field(_date_struct(status, "lastUpdatePostDateStruct")),
        trial=TrialDetails(
            study_type=text_field(design.get("studyType")),
            overall_status=text_field(status.get("overallStatus")),
            phases=_optional_list(design.get("phases")),
            conditions=condition_list,
        ),
    )


class ClinicalTrials:
    def __init__(self, http: SourceHttp):
        self._http = http

    async def pages(self, window: CollectionWindow) -> AsyncIterator[list[Material]]:
        start, end = date_range(window)
        params: dict[str, str | int] = {
            "query.term": f"({window.query}) AND AREA[LastUpdatePostDate]RANGE[{start},{end}]",
            "fields": FIELDS,
            "format": "json",
            "pageSize": PAGE_SIZE,
            "sort": "LastUpdatePostDate:asc",
        }
        used_tokens: set[str] = set()
        for _ in range(MAX_PAGES):
            data = await self._http.get_json(HOST, "/api/v2/studies", params)
            studies = list_field(data.get("studies"))
            next_token = required_text(data["nextPageToken"]) if "nextPageToken" in data else None
            if next_token is not None and next_token in used_tokens:
                raise WorkerError(FailureCode.BAD_SOURCE_DATA)
            try:
                if studies:
                    yield [_record(object_field(item)) for item in studies]
            except ValidationError:
                raise WorkerError(FailureCode.BAD_SOURCE_DATA) from None
            if next_token is None:
                return
            params["pageToken"] = next_token
            used_tokens.add(next_token)
        raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
