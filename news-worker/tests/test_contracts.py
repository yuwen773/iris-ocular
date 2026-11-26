import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from iris_news_worker.contracts import Material
from tools.export_schema import MODELS, build_schema

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "news"


def load(relative: str):
    return json.loads((CONTRACT / relative).read_text(encoding="utf-8"))


def test_shared_wire_fixtures_match_schema_bindings_and_openapi():
    schema = load("v1.schema.json")
    assert build_schema() == schema
    Draft202012Validator.check_schema(schema)
    api = load("openapi.json")
    fixtures = load("fixtures/v1.json")
    assert set(fixtures) == {model.__name__ for model in MODELS}
    assert api["components"]["securitySchemes"]["WorkerKey"]["name"] == "X-Iris-News-Worker-Key"
    assert api["servers"][0]["url"] == "/api/internal/news/v1/leases"
    for model in MODELS:
        name = model.__name__
        validator = Draft202012Validator(
            {**schema, "$ref": f"#/$defs/{name}"},
            format_checker=Draft202012Validator.FORMAT_CHECKER,
        )
        validator.validate(fixtures[name])
        assert (
            json.loads(model.model_validate_json(json.dumps(fixtures[name])).wire_bytes())
            == fixtures[name]
        )
        assert f"./v1.schema.json#/$defs/{name}" in json.dumps(api)


@pytest.mark.parametrize("case", load("fixtures/v1.invalid.json"), ids=lambda case: case["name"])
def test_rejects_shared_semantic_negative_fixture(case):
    fixtures = load("fixtures/v1.json")
    fixtures["Material"] = fixtures["Batch"]["records"][0]
    models = {model.__name__: model for model in (*MODELS, Material)}
    model = case["model"]
    payload = {**fixtures[model], **case["patch"]}
    with pytest.raises(ValidationError):
        models[model].model_validate_json(json.dumps(payload), by_name=False)
