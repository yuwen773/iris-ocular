from calendar import month_abbr, month_name
from collections.abc import AsyncIterator
from datetime import date
from xml.etree.ElementTree import Element, ParseError

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException
from pydantic import ValidationError

from iris_news_worker.adapters.fields import PAGE_SIZE, date_range, object_field, required_text
from iris_news_worker.contracts import (
    MAX_WINDOW_RECORDS,
    CollectionWindow,
    FailureCode,
    Material,
    Source,
)
from iris_news_worker.errors import WorkerError
from iris_news_worker.transport import SourceHttp

HOST = "eutils.ncbi.nlm.nih.gov"
MONTHS = {
    name.lower(): index
    for names in (month_abbr, month_name)
    for index, name in enumerate(names)
    if name
}


def _text(node: Element | None, path: str) -> str | None:
    child = node.find(path) if node is not None else None
    if child is None:
        return None
    return " ".join("".join(child.itertext()).split()) or None


def _publication_date(node: Element | None) -> str | None:
    if node is None:
        return None
    year = _text(node, "Year")
    if year is None and (medline_date := _text(node, "MedlineDate")) is not None:
        prefix = medline_date[:4]
        return prefix if prefix.isascii() and prefix.isdecimal() else None
    if year is None:
        return None
    month = _text(node, "Month")
    if month is None:
        return year
    if month.isascii() and month.isdecimal():
        month_number = int(month)
    else:
        month_number = MONTHS.get(month.lower())
        if month_number is None:
            # Ranges such as Jan-Feb have year precision in this projection.
            return year
    day = _text(node, "Day")
    return (
        f"{year}-{month_number:02d}" if day is None else f"{year}-{month_number:02d}-{int(day):02d}"
    )


def _record(entry: Element) -> Material:
    citation = entry.find("MedlineCitation")
    if entry.tag == "PubmedBookArticle":
        record = entry.find("BookDocument")
        title = _text(record, "ArticleTitle") or _text(record, "Book/BookTitle")
        pub_date = None if record is None else record.find("Book/PubDate")
        language = _text(record, "Language")
        doi = _text(record, "ArticleIdList/ArticleId[@IdType='doi']")
    elif citation is not None and entry.tag == "PubmedArticle":
        record = citation
        title = _text(record, "Article/ArticleTitle")
        pub_date = record.find("Article/ArticleDate")
        if pub_date is None:
            pub_date = record.find("Article/Journal/JournalIssue/PubDate")
        language = _text(record, "Article/Language")
        doi = _text(entry, "PubmedData/ArticleIdList/ArticleId[@IdType='doi']")
    else:
        raise WorkerError(FailureCode.BAD_SOURCE_DATA)
    pmid = required_text(_text(record, "PMID"))
    revision = _publication_date(None if citation is None else citation.find("DateRevised"))
    return Material(
        source=Source.PUBMED,
        source_record_id=pmid,
        url=f"https://pubmed.ncbi.nlm.nih.gov/{pmid}/",
        title=required_text(title),
        language=language,
        published_on=_publication_date(pub_date),
        updated_on=date.fromisoformat(revision) if revision is not None else None,
        pmid=pmid,
        doi=doi,
    )


class PubMed:
    def __init__(self, http: SourceHttp, email: str | None):
        self._http = http
        self._identity = {"tool": "iris_news_worker"}
        if email:
            self._identity["email"] = email

    async def pages(self, window: CollectionWindow) -> AsyncIterator[list[Material]]:
        start, end = (value.replace("-", "/") for value in date_range(window))
        query = f"({window.query}) AND ({start}:{end}[crdt] OR {start}:{end}[lr])"
        payload = await self._http.get_json(
            HOST,
            "/entrez/eutils/esearch.fcgi",
            dict(self._identity, db="pubmed", term=query, usehistory="y", retmode="json", retmax=0),
        )
        result = object_field(payload.get("esearchresult"))
        count_str = required_text(result.get("count"))
        if not count_str.isascii() or not count_str.isdecimal() or len(count_str) > 12:
            raise WorkerError(FailureCode.BAD_SOURCE_DATA)
        count = int(count_str)
        if count >= MAX_WINDOW_RECORDS:
            raise WorkerError(FailureCode.WINDOW_TOO_LARGE)
        if not count:
            return
        history = required_text(result.get("webenv"))
        query_key = required_text(result.get("querykey"))
        for start_index in range(0, count, PAGE_SIZE):
            body = await self._http.get(
                HOST,
                "/entrez/eutils/efetch.fcgi",
                dict(
                    self._identity,
                    db="pubmed",
                    WebEnv=history,
                    query_key=query_key,
                    retmode="xml",
                    retstart=start_index,
                    retmax=PAGE_SIZE,
                ),
            )
            try:
                root = ElementTree.fromstring(body)
                if root.tag != "PubmedArticleSet" or len(root) != min(
                    PAGE_SIZE, count - start_index
                ):
                    raise WorkerError(FailureCode.BAD_SOURCE_DATA)
                records = [_record(entry) for entry in root]
            except (DefusedXmlException, ParseError, ValidationError, ValueError):
                raise WorkerError(FailureCode.BAD_SOURCE_DATA) from None
            yield records
