"""线协议上的质量状态只有一个来源：``QualityReport.state``。

映射函数本身在 ``grpc_server``（要 proto 枚举），这里用替身模块导入它，
断言「产品状态 → 线协议枚举」这一跳的取值，以及认不出的状态不会被猜成 PASSED。
真正的判定（state 由 reasons 算出）在 ``eye_diagnosis/tests/test_quality.py`` 里钉住。
"""

import sys
import types
from pathlib import Path

import pytest

pytest.importorskip("grpc")

_PROTO_NAMES = (
    "QUALITY_UNSPECIFIED",
    "QUALITY_PASSED",
    "QUALITY_FAILED",
    "QUALITY_NOT_EVALUATED",
)
_PROTO_FIELDS = {name: index for index, name in enumerate(_PROTO_NAMES, start=1)}


def _import_grpc_server(monkeypatch):
    """用替身撑起 protobuf 与模型模块，只导入模块级定义，不构造服务。"""
    pb2 = types.ModuleType("diagnosis_pb2")
    for name, value in _PROTO_FIELDS.items():
        setattr(pb2, name, value)
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


def test_quality_state_maps_to_the_wire_enum(monkeypatch):
    from eye_diagnosis.quality import QualityState

    grpc_server = _import_grpc_server(monkeypatch)

    assert grpc_server._proto_quality_state(QualityState.PASSED) == _PROTO_FIELDS["QUALITY_PASSED"]
    assert grpc_server._proto_quality_state(QualityState.FAILED) == _PROTO_FIELDS["QUALITY_FAILED"]


def test_unknown_state_stays_unspecified(monkeypatch):
    grpc_server = _import_grpc_server(monkeypatch)

    # 「算法没给」不能被猜成「通过」：认不出来就是 UNSPECIFIED
    assert grpc_server._proto_quality_state(None) == _PROTO_FIELDS["QUALITY_UNSPECIFIED"]
    assert grpc_server._proto_quality_state("SOMETHING_ELSE") == _PROTO_FIELDS["QUALITY_UNSPECIFIED"]
