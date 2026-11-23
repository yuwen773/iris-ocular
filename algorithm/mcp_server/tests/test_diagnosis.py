import pytest
from mcp_server.diagnosis import build_diagnosis_result


def test_build_result_basic():
    model_result = {
        "predicted_label": "glaucoma",
        "probability": 0.875,
        "all_probabilities": {
            "normal": 0.052,
            "amd": 0.015,
            "cataract": 0.031,
            "diabetes": 0.010,
            "glaucoma": 0.875,
            "hypertension": 0.008,
            "myopia": 0.005,
            "other": 0.004,
        },
    }
    result = build_diagnosis_result(model_result)

    assert result["predicted_label"] == "glaucoma"
    assert result["predicted_label_cn"] == "青光眼"
    assert result["probability"] == 87.5
    assert result["top2_margin"] == pytest.approx(0.875 - 0.052)
    assert "青光眼" in result["description"]
    assert len(result["top_predictions"]) == 3
    assert result["top_predictions"][0]["label"] == "glaucoma"
    assert result["top_predictions"][0]["label_cn"] == "青光眼"


def test_build_result_low_confidence():
    model_result = {
        "predicted_label": "other",
        "probability": 0.15,
        "all_probabilities": {
            "normal": 0.14,
            "amd": 0.12,
            "cataract": 0.13,
            "diabetes": 0.11,
            "glaucoma": 0.10,
            "hypertension": 0.12,
            "myopia": 0.13,
            "other": 0.15,
        },
    }
    result = build_diagnosis_result(model_result)
    assert result["top2_margin"] == pytest.approx(0.15 - 0.14)


def test_build_result_probability_scaling():
    model_result = {
        "predicted_label": "normal",
        "probability": 0.5,
        "all_probabilities": {
            "normal": 0.5,
            "amd": 0.2,
            "cataract": 0.1,
            "diabetes": 0.05,
            "glaucoma": 0.05,
            "hypertension": 0.05,
            "myopia": 0.03,
            "other": 0.02,
        },
    }
    result = build_diagnosis_result(model_result)
    assert result["probability"] == 50.0
    assert result["top2_margin"] == pytest.approx(0.5 - 0.2)


def test_top2_margin_is_a_measurement_not_a_score():
    """差距原样返回：不再映射成 95/85/75 这种「可靠性」分数。"""
    from mcp_server.diagnosis import top2_margin

    assert top2_margin({"a": 0.75, "b": 0.25, "c": 0.0}) == pytest.approx(0.5)
    assert top2_margin({"a": 0.55, "b": 0.45}) == pytest.approx(0.1)
    assert top2_margin({"a": 1.0}) is None
