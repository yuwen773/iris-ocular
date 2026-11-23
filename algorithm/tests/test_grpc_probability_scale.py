"""原始 softmax 与校准后概率不能互相冒充。

proto 里 ``probability`` / ``classification_probability`` / ``reliability_score``
的语义都是「原始 softmax」，``calibrated_probability`` 才是校准后的值。
开校准之前两者数值相同，所以混淆不会自己暴露；这里直接钉住
``_probabilities`` 的两个出口：未校准时相同，校准后必须不同且校准后更平。
"""

import sys
import types
from pathlib import Path

import pytest

pytest.importorskip("grpc")
np = pytest.importorskip("numpy")


def _import_grpc_server(monkeypatch):
    """替身撑起 protobuf 与模型模块，只导入模块级定义（不构造服务）。"""
    pb2 = types.ModuleType("diagnosis_pb2")
    for name in ("QUALITY_UNSPECIFIED", "QUALITY_PASSED", "QUALITY_FAILED",
                 "QUALITY_NOT_EVALUATED"):
        setattr(pb2, name, name)
    for cls in ("DiagnosisResponse", "DiseasePrediction", "DiagnosisRequest", "HealthCheckResponse"):
        setattr(pb2, cls, object)
    monkeypatch.setitem(sys.modules, "diagnosis_pb2", pb2)

    pb2_grpc = types.ModuleType("diagnosis_pb2_grpc")
    pb2_grpc.DiagnosisServiceServicer = type("DiagnosisServiceServicer", (), {})
    pb2_grpc.add_DiagnosisServiceServicer_to_server = lambda *args, **kwargs: None
    monkeypatch.setitem(sys.modules, "diagnosis_pb2_grpc", pb2_grpc)

    model = types.ModuleType("eye_disease_model")
    model.EyeDiseaseModel = object
    model.disease_categories = {}
    monkeypatch.setitem(sys.modules, "eye_disease_model", model)

    algorithm_dir = Path(__file__).resolve().parents[1]
    monkeypatch.syspath_prepend(str(algorithm_dir))
    monkeypatch.delitem(sys.modules, "grpc_server", raising=False)
    import grpc_server

    return grpc_server


def test_uncalibrated_returns_the_same_distribution(monkeypatch):
    grpc_server = _import_grpc_server(monkeypatch)
    logits = np.array([3.0, 1.0, 0.5, 0.0])

    raw, reported = grpc_server._probabilities(logits, "NOT_CALIBRATED", 1.0)

    assert np.allclose(raw, reported)


def test_calibration_flattens_the_reported_distribution_only(monkeypatch):
    grpc_server = _import_grpc_server(monkeypatch)
    logits = np.array([3.0, 1.0, 0.5, 0.0])

    raw, reported = grpc_server._probabilities(logits, "CALIBRATED", 2.0)

    # 温度 >1 让分布变平：校准后的 top1 更低，但「原始分布」必须原样保留
    assert reported.max() < raw.max()
    assert np.isclose(raw.sum(), 1.0)
    assert np.isclose(reported.sum(), 1.0)
    # 报告的 top1 与校准后分布一致，而不是拿校准值冒充原始值
    assert np.isclose(raw.max(), float(grpc_server._softmax(logits).max()))
