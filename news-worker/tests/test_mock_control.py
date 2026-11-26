import httpx

from iris_news_worker.contracts import Batch, Complete
from iris_news_worker.control import CONTROL_PREFIX, WORKER_KEY_HEADER
from tests.mock_control import BASE, KEY, MockControl
from tests.test_contracts import load


def test_fencing_and_window_integrity_on_wire():
    mock = MockControl()
    data = load("fixtures/v1.json")
    batch = Batch.model_validate(data["Batch"], strict=False)
    complete = Complete.model_validate(data["Complete"], strict=False)
    with httpx.Client(
        base_url=BASE + CONTROL_PREFIX + "/",
        headers={WORKER_KEY_HEADER: KEY},
        transport=httpx.MockTransport(mock.handle),
    ) as client:
        assert client.post("claim").status_code == 200
        assert client.post(
            "task-001/complete",
            content=complete.wire_bytes(),
            headers={"Idempotency-Key": "1:complete"},
        ).json() == {"code": "INCOMPLETE_BATCHES"}

        def post(item):
            return client.post(
                "task-001/batches",
                content=item.wire_bytes(),
                headers={"Idempotency-Key": f"{item.generation}:{item.sequence}"},
            )

        assert post(batch).status_code == 200
        assert mock.visible == {}
        mock.next_attempt()
        assert client.post("claim").status_code == 200
        assert post(batch).status_code == 200
        changed = batch.model_copy(
            update={"records": [batch.records[0].model_copy(update={"title": "changed"})]}
        )
        assert post(changed).json() == {"code": "IDEMPOTENCY_CONFLICT"}
        assert post(batch.model_copy(update={"sequence": 2})).json() == {"code": "STALE_LEASE"}
        assert mock.batches == {}
