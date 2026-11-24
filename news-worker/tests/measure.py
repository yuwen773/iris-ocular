"""Measure the CLI with fixed fixtures in a fresh process per scenario."""

import argparse
import json
import sys
from collections import deque
from time import perf_counter, process_time

import httpx
import pytest
import respx

from iris_news_worker import cli
from tests.mock_control import MockControl
from tests.support import TRIALS, configure_cli, trial, trial_page


def peak_bytes() -> int:
    if sys.platform != "win32":
        import resource

        peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return peak if sys.platform == "darwin" else peak * 1024

    import ctypes
    from ctypes.wintypes import DWORD, HANDLE

    class MemoryCounters(ctypes.Structure):
        _fields_ = [
            ("size", DWORD),
            ("pageFaultCount", DWORD),
            ("peakWorkingSet", ctypes.c_size_t),
            ("otherCounters", ctypes.c_size_t * 7),
        ]

    counters = MemoryCounters()
    counters.size = ctypes.sizeof(counters)
    current_process = ctypes.windll.kernel32.GetCurrentProcess
    current_process.restype = HANDLE
    get_counters = ctypes.windll.psapi.GetProcessMemoryInfo
    get_counters.argtypes = (HANDLE, ctypes.POINTER(MemoryCounters), DWORD)
    if not get_counters(current_process(), ctypes.byref(counters), counters.size):
        raise OSError("GetProcessMemoryInfo failed")
    return counters.peakWorkingSet


def measure(scenario: str) -> None:
    mock = MockControl()
    size = 0
    if scenario == "startup":
        mock.claims.clear()
        responses = []
    elif scenario == "normal":
        size = 2
        responses = [trial_page([trial()], "next"), trial_page([trial(2)])]
    elif scenario == "dense":
        size = 4_000
        responses = [
            trial_page(
                [trial(number) for number in range(index + 1, index + 51)],
                str(index + 50) if index + 50 < size else None,
            )
            for index in range(0, size, 50)
        ]
    else:
        responses = [httpx.Response(429), httpx.Response(503), httpx.Response(503)]
    pending = deque(responses)
    started = perf_counter()
    cpu_started = process_time()
    with pytest.MonkeyPatch.context() as patches, respx.mock:
        configure_cli(patches, mock)
        if pending:
            respx.get(url__startswith=TRIALS).mock(side_effect=lambda _: pending.popleft())
        result = cli.main(["run-once", "--concurrency", "1"])
    assert result == int(scenario == "retry")
    assert len(mock.visible) == size
    assert not pending
    print(
        json.dumps(
            {
                "scenario": scenario,
                "wallSeconds": round(perf_counter() - started, 4),
                "cpuSeconds": round(process_time() - cpu_started, 4),
                "peakWorkingSetMiB": round(peak_bytes() / (1024 * 1024), 2),
                "sourceRequests": len(responses),
                "controlRequests": len(mock.calls),
                "committedRevisions": len(mock.visible),
            }
        )
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("scenario", choices=("startup", "normal", "dense", "retry"))
    measure(parser.parse_args().scenario)
