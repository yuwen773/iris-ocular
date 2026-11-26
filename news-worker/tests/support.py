from functools import partial

import httpx
import respx

from iris_news_worker import cli
from iris_news_worker.transport import SOURCE_INTERVALS, SourceHttp
from tests.mock_control import BASE, KEY, MockControl

TEST_IP = "93.184.216.34"
CLINICAL_TRIALS_HOST = "clinicaltrials.gov"
SOURCE_ORIGIN = f"https://{TEST_IP}"
TRIALS = SOURCE_ORIGIN + "/api/v2/studies"
EUROPE = SOURCE_ORIGIN + "/europepmc/webservices/rest/search"
PUBMED_SEARCH = SOURCE_ORIGIN + "/entrez/eutils/esearch.fcgi"
PUBMED_FETCH = SOURCE_ORIGIN + "/entrez/eutils/efetch.fcgi"


async def public_dns(_host: str) -> tuple[str, ...]:
    return (TEST_IP,)


def configure_cli(monkeypatch, mock: MockControl, *, intervals=None, resolver=public_dns) -> None:
    if intervals is None:
        intervals = dict.fromkeys(SOURCE_INTERVALS, 0)
    monkeypatch.setattr(
        cli, "SourceHttp", partial(SourceHttp, resolver=resolver, intervals=intervals)
    )
    monkeypatch.setenv("IRIS_NEWS_CONTROL_URL", BASE)
    monkeypatch.setenv("IRIS_NEWS_WORKER_KEY", KEY)
    respx.post(url__startswith=BASE).mock(side_effect=mock.handle)


def trial(number: int = 1, status: str = "RECRUITING"):
    return {
        "protocolSection": {
            "identificationModule": {
                "nctId": f"NCT{number:08d}",
                "briefTitle": "Synthetic glaucoma example",
            },
            "statusModule": {
                "studyFirstPostDateStruct": {"date": "2026-09"},
                "lastUpdatePostDateStruct": {"date": "2026-10-09"},
                "overallStatus": status,
            },
            "designModule": {"studyType": "INTERVENTIONAL", "phases": ["PHASE2"]},
            "conditionsModule": {"conditions": ["Glaucoma"]},
        },
    }


def trial_page(studies=(), token: str | None = None) -> httpx.Response:
    payload = {"studies": list(studies)}
    if token is not None:
        payload["nextPageToken"] = token
    return httpx.Response(200, json=payload)


def pmc_page(items=(), *, cursor: str | None = None, count: int = 0) -> httpx.Response:
    payload = {"hitCount": count, "resultList": {"result": list(items)}}
    if cursor is not None:
        payload["nextCursorMark"] = cursor
    return httpx.Response(200, json=payload)


def pubmed_count(count: int) -> httpx.Response:
    return httpx.Response(
        200,
        json={"esearchresult": {"count": str(count), "webenv": "fake-history", "querykey": "1"}},
    )


def pubmed_articles(start: int, count: int) -> httpx.Response:
    entries = [
        f"""<PubmedArticle><MedlineCitation><PMID>{number}</PMID>
          <DateRevised><Year>2026</Year><Month>10</Month><Day>12</Day></DateRevised>
          <Article><ArticleTitle>Eye <i>study</i> {number}</ArticleTitle>
          <Language>eng</Language>
          <Journal><JournalIssue><PubDate><Year>2026</Year><Month>Oct</Month></PubDate>
          </JournalIssue></Journal></Article></MedlineCitation>
          <PubmedData><ArticleIdList><ArticleId IdType="doi">10.1/{number}</ArticleId>
          </ArticleIdList></PubmedData></PubmedArticle>"""
        for number in range(start, start + count)
    ]
    return httpx.Response(200, text="<PubmedArticleSet>" + "".join(entries) + "</PubmedArticleSet>")
