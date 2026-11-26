"""Regenerate the checked-in language-neutral v1 JSON Schema."""

import json
from pathlib import Path

from pydantic.json_schema import models_json_schema

from iris_news_worker.contracts import (
    Batch,
    BatchReceipt,
    Complete,
    CompleteReceipt,
    ControlError,
    Fail,
    FailureReceipt,
    Lease,
)

MODELS = (Lease, Batch, BatchReceipt, Complete, CompleteReceipt, Fail, FailureReceipt, ControlError)


def build_schema() -> dict:
    _, definitions = models_json_schema([(model, "validation") for model in MODELS], by_alias=True)
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "title": "Iris News Engine v1 payloads",
        **definitions,
    }


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[2] / "contracts" / "news" / "v1.schema.json"
    output.write_text(
        json.dumps(build_schema(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
