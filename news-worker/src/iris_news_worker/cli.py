"""Single-run CLI and machine-readable, sanitized run receipts."""

import argparse
import asyncio
import json
import math
import os
import signal
import sys
from datetime import UTC, datetime
from time import monotonic

import httpx

from iris_news_worker.adapters.clinical_trials import ClinicalTrials
from iris_news_worker.adapters.europe_pmc import EuropePmc
from iris_news_worker.adapters.pubmed import PubMed
from iris_news_worker.contracts import Source
from iris_news_worker.control import ControlClient
from iris_news_worker.runtime import MAX_CONCURRENCY, MAX_RUN_SECONDS, Worker
from iris_news_worker.transport import SourceHttp, new_http_client


async def _run(concurrency: int, seconds: float) -> int:
    started = monotonic()
    started_at = datetime.now(UTC)
    stopping = asyncio.Event()
    loop = asyncio.get_running_loop()

    def on_signal(_number, _frame):
        loop.call_soon_threadsafe(stopping.set)

    signals = (signal.SIGINT, signal.SIGTERM)
    previous = {sig: signal.signal(sig, on_signal) for sig in signals}
    try:
        requests = asyncio.Semaphore(MAX_CONCURRENCY)
        async with (
            new_http_client(MAX_CONCURRENCY, source=True) as outbound,
            new_http_client(MAX_CONCURRENCY, source=False) as internal,
        ):
            control = ControlClient(
                internal,
                os.environ["IRIS_NEWS_CONTROL_URL"],
                os.environ["IRIS_NEWS_WORKER_KEY"],
                requests,
            )
            sources = SourceHttp(outbound, requests)
            worker = Worker(
                control,
                {
                    Source.PUBMED: PubMed(sources, os.getenv("IRIS_NEWS_NCBI_EMAIL")),
                    Source.EUROPE_PMC: EuropePmc(sources),
                    Source.CLINICAL_TRIALS: ClinicalTrials(sources),
                },
            )
            results = await worker.run_once(
                concurrency=concurrency, seconds=seconds, stopping=stopping
            )
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
    print(
        json.dumps(
            {
                "startedAt": started_at.isoformat(),
                "elapsedSeconds": round(monotonic() - started, 3),
                "results": [
                    {
                        "status": result.status,
                        "code": result.code,
                        "failureReported": result.reported,
                    }
                    for result in results
                ],
            }
        )
    )
    return int(any(result.status == "FAILED" for result in results))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="iris-news-worker")
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run-once", help="Claim and execute a bounded set of leases once")
    run.add_argument("--concurrency", type=int, choices=range(1, MAX_CONCURRENCY + 1), default=4)
    run.add_argument("--seconds", type=float, default=MAX_RUN_SECONDS)
    args = parser.parse_args(argv)
    if not math.isfinite(args.seconds) or not 0 < args.seconds <= MAX_RUN_SECONDS:
        parser.error(f"--seconds must be in (0, {MAX_RUN_SECONDS}]")
    try:
        return asyncio.run(_run(args.concurrency, args.seconds))
    except (KeyError, ValueError, httpx.InvalidURL):
        print('{"status":"FAILED","code":"INVALID_CONFIGURATION"}', file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
