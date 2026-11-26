import asyncio
import json
import signal
from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx

from iris_news_worker import cli, runtime
from iris_news_worker.adapters import europe_pmc
from iris_news_worker.contracts import FailureCode, Source
from tests.mock_control import MockControl
from tests.support import (
    EUROPE,
    PUBMED_FETCH,
    PUBMED_SEARCH,
    TRIALS,
    configure_cli,
    pmc_page,
    pubmed_articles,
    pubmed_count,
    trial,
    trial_page,
)

CLI = ["run-once", "--concurrency", "1"]


@respx.mock
def test_cli_replays_lost_receipts_and_keeps_changed_revision(monkeypatch, capsys):
    mock = MockControl()
    mock.drop_after_commit.update({"batches", "complete"})
    configure_cli(monkeypatch, mock)
    pages = respx.get(url__startswith=TRIALS).mock(
        side_effect=[
            trial_page([trial()], "token-2"),
            trial_page([], "token-3"),
            trial_page([trial(), trial(status="COMPLETED")]),
        ]
    )
    assert cli.main(CLI) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["results"] == [{"status": "COMPLETED", "code": None, "failureReported": False}]
    assert mock.complete_cursor == mock.lease.window.end_before
    assert mock.calls.count("/task-001/batches") == 3
    assert mock.calls.count("/task-001/complete") == 2
    assert len(mock.visible) == 2
    first, second, third = (call.request for call in pages.calls)
    assert first.headers["Host"] == "clinicaltrials.gov"
    assert first.extensions["sni_hostname"] == "clinicaltrials.gov"
    assert "X-Iris-News-Worker-Key" not in first.headers
    assert first.url.params["query.term"].endswith("RANGE[2026-10-01,2026-10-14]")
    assert [second.url.params["pageToken"], third.url.params["pageToken"]] == ["token-2", "token-3"]
    assert first.url.params["query.term"] == third.url.params["query.term"]


@respx.mock
def test_pubmed_multi_page_preserves_history_and_date_precision(monkeypatch):
    mock = MockControl(Source.PUBMED)
    configure_cli(monkeypatch, mock)
    search = respx.get(url__startswith=PUBMED_SEARCH).mock(return_value=pubmed_count(51))
    fetch = respx.get(url__startswith=PUBMED_FETCH).mock(
        side_effect=[pubmed_articles(1, 50), pubmed_articles(51, 1)]
    )
    assert cli.main(CLI) == 0
    assert "2026/10/14[lr]" in search.calls[0].request.url.params["term"]
    assert [call.request.url.params["retstart"] for call in fetch.calls] == ["0", "50"]
    assert all(call.request.url.params["WebEnv"] == "fake-history" for call in fetch.calls)
    assert len(mock.visible) == 51
    sample = next(iter(mock.visible.values()))
    assert (sample.pmid, sample.title, sample.published_on) == ("1", "Eye study 1", "2026-10")


@respx.mock
def test_europe_pmc_cursor_and_abstract_rights(monkeypatch):
    mock = MockControl(Source.EUROPE_PMC)
    configure_cli(monkeypatch, mock)
    monkeypatch.setattr(europe_pmc, "MAX_PAGES", 2)
    item = {
        "id": "1",
        "source": "MED",
        "title": "Synthetic title",
        "pubYear": "2026",
        "abstractText": "Synthetic abstract",
        "license": "CC BY 4.0",
    }
    other = dict(item, id="2", license="CC BY-NC 4.0")
    route = respx.get(url__startswith=EUROPE).mock(
        side_effect=[
            pmc_page([item], cursor="b", count=2),
            pmc_page([other], cursor="c", count=2),
            pmc_page(cursor="c", count=2),
        ]
    )
    assert cli.main(CLI) == 0
    records = list(mock.visible.values())
    assert [(r.source_record_id, r.abstract_license) for r in records] == [
        ("MED:1", "CC-BY"),
        ("MED:2", None),
    ]
    assert [call.request.url.params["cursorMark"] for call in route.calls] == ["*", "b", "c"]


@respx.mock
def test_europe_pmc_single_page_window_without_cursor_commits(monkeypatch):
    # Europe PMC omits nextCursorMark when the window fits on one page; that is a
    # complete window, not malformed data.
    mock = MockControl(Source.EUROPE_PMC)
    configure_cli(monkeypatch, mock)
    item = {"id": "1", "source": "MED", "title": "Synthetic title", "pubYear": "2026"}
    respx.get(url__startswith=EUROPE).mock(return_value=pmc_page([item], count=1))
    assert cli.main(CLI) == 0
    assert mock.complete_cursor == mock.lease.window.end_before
    assert [record.source_record_id for record in mock.visible.values()] == ["MED:1"]


@respx.mock
def test_europe_pmc_final_page_short_of_hit_count_fails_closed(monkeypatch):
    # A page with no cursor that does not account for every hit is still rejected,
    # and no partial batch may become visible.
    mock = MockControl(Source.EUROPE_PMC)
    configure_cli(monkeypatch, mock)
    item = {"id": "1", "source": "MED", "title": "Synthetic title", "pubYear": "2026"}
    respx.get(url__startswith=EUROPE).mock(return_value=pmc_page([item], count=5))
    assert cli.main(CLI) == 1
    assert mock.failures[0].code == FailureCode.BAD_SOURCE_DATA
    assert not mock.visible and mock.complete_cursor is None


@respx.mock
def test_clinical_trials_projects_at_most_one_hundred_conditions_and_continues(monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    first = trial()
    conditions = [f"Condition {index}" for index in range(105)]
    first["protocolSection"]["conditionsModule"] = {"conditions": conditions}
    respx.get(url__startswith=TRIALS).mock(
        side_effect=[trial_page([first], "page-2"), trial_page([trial(2)])]
    )

    assert cli.main(CLI) == 0
    assert mock.complete_cursor == mock.lease.window.end_before
    assert len(mock.visible) == 2
    assert list(mock.visible.values())[0].trial.conditions == conditions[:100]


@pytest.mark.parametrize(
    ("source", "url", "empty"),
    [
        (Source.PUBMED, PUBMED_SEARCH, pubmed_count(0)),
        (Source.EUROPE_PMC, EUROPE, pmc_page()),
        (Source.CLINICAL_TRIALS, TRIALS, trial_page()),
    ],
)
@respx.mock
def test_empty_windows_commit_with_zero_batches(source, url, empty, monkeypatch):
    mock = MockControl(source)
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=url).mock(return_value=empty)
    assert cli.main(CLI) == 0
    assert mock.complete_cursor == mock.lease.window.end_before
    assert not mock.batches


@respx.mock
def test_bad_record_hides_partial_batches_until_whole_window_restarts(monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=TRIALS).mock(
        side_effect=[
            trial_page([trial()], "another"),
            trial_page([{"protocolSection": {}}]),
            trial_page([trial(), trial(2)]),
        ]
    )
    assert cli.main(CLI) == 1
    assert mock.failures[0].code == FailureCode.BAD_SOURCE_DATA
    assert mock.batches and not mock.visible and mock.complete_cursor is None
    mock.next_attempt()
    assert cli.main(CLI) == 0
    assert mock.complete_cursor == mock.lease.window.end_before
    assert len(mock.visible) == 2


@respx.mock
def test_oversized_window_reports_terminal_code(monkeypatch):
    mock = MockControl(Source.PUBMED)
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=PUBMED_SEARCH).mock(return_value=pubmed_count(10_000))
    assert cli.main(CLI) == 1
    assert mock.failures[0].code == FailureCode.WINDOW_TOO_LARGE
    assert mock.complete_cursor is None


@respx.mock
def test_control_rejects_credentialed_redirect_without_following(monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    respx.post(url__startswith="http://127.0.0.1:8090").mock(
        return_value=httpx.Response(307, headers={"Location": "https://example.com/"})
    )
    assert cli.main(CLI) == 1
    assert len(respx.calls) == 1


@respx.mock
def test_expired_claim_does_not_issue_source_requests(monkeypatch):
    mock = MockControl()
    mock.claims[0] = mock.lease.model_copy(
        update={"lease_expires_at": datetime.now(UTC) - timedelta(seconds=1)}
    )
    configure_cli(monkeypatch, mock)
    assert cli.main(CLI) == 1
    assert mock.calls == ["/claim"]


@respx.mock
def test_signal_interrupt_preserves_cursor_and_next_run_recovers(monkeypatch, capsys):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    monkeypatch.setattr(runtime, "SHUTDOWN_GRACE_SECONDS", 0)

    async def interrupted(_request):
        signal.raise_signal(signal.SIGINT)
        await asyncio.sleep(30)
        raise AssertionError("worker failed to interrupt the fetch")

    route = respx.get(url__startswith=TRIALS).mock(side_effect=interrupted)
    assert cli.main(CLI) == 1
    assert "INTERRUPTED" in capsys.readouterr().out
    assert mock.complete_cursor is None
    mock.next_attempt()
    route.mock(return_value=trial_page([trial()]))
    assert cli.main(CLI) == 0
    assert len(mock.visible) == 1


@respx.mock
def test_uncertain_completion_leaves_recovery_to_control_plane(monkeypatch):
    mock = MockControl()
    mock.drop_after_commit.add("complete")
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=TRIALS).mock(return_value=trial_page())
    assert cli.main([*CLI, "--seconds", "0.20"]) == 1
    assert mock.complete_cursor is not None
    assert not mock.failures


@respx.mock
def test_present_but_invalid_clinical_trials_token_cannot_commit(monkeypatch):
    mock = MockControl()
    configure_cli(monkeypatch, mock)
    respx.get(url__startswith=TRIALS).mock(
        return_value=httpx.Response(200, json={"studies": [trial()], "nextPageToken": None})
    )
    assert cli.main(CLI) == 1
    assert mock.failures[0].code == FailureCode.BAD_SOURCE_DATA
    assert mock.complete_cursor is None
