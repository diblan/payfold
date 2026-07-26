import asyncio
import json

import httpx

from app.config import Settings
from app.main import create_app
from app.signing import verify


def settings(**overrides):
    values = {
        "bank_id": "bank-test",
        "scheme": "sepa_core",
        "webhook_url": "http://sink/webhook",
        "webhook_secret": "test-secret",
        "settlement_delay_seconds": 0.02,
        "chargeback_lag_seconds": 0.03,
        "retry_max_attempts": 3,
        "retry_backoff_seconds": 0,
    }
    values.update(overrides)
    return Settings(**values)


def submission(collection_id="collection-1", iban="BE68539007547034"):
    return {
        "collection_id": collection_id,
        "amount_cents": 1299,
        "currency": "EUR",
        "debtor_iban": iban,
        "mandate_reference": "mandate-1",
        "due_date": "2026-07-26",
    }


async def wait_for_requests(requests, count):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + 2
    while len(requests) < count and loop.time() < deadline:
        await asyncio.sleep(0.02)
    assert len(requests) == count


async def clients(handler, bank_settings=None):
    webhook_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    application = create_app(bank_settings or settings(), webhook_client)
    api_client = httpx.AsyncClient(
        transport=httpx.ASGITransport(app=application),
        base_url="http://t",
    )
    return api_client, webhook_client


async def test_clean_submission_delivers_one_signed_webhook():
    captured = []

    async def handler(request):
        captured.append(request)
        return httpx.Response(200, request=request)

    api, webhook = await clients(handler)
    async with api, webhook:
        response = await api.post("/collections", json=submission())
        assert response.status_code == 202
        assert response.json() == {
            "collection_id": "collection-1",
            "status": "accepted",
            "duplicate": False,
        }
        await wait_for_requests(captured, 1)

        request = captured[0]
        assert request.headers["X-Bank-Id"] == "bank-test"
        assert verify(
            request.content,
            "test-secret",
            request.headers["X-Bank-Signature"],
        )
        payload = json.loads(request.content)
        assert payload.keys() == {
            "schema_version",
            "bank_id",
            "notification_id",
            "collection_id",
            "outcome",
            "reason",
            "occurred_at",
        }
        assert payload["schema_version"] == 1
        assert payload["bank_id"] == "bank-test"
        assert payload["notification_id"] == "collection-1:1"
        assert payload["collection_id"] == "collection-1"
        assert payload["outcome"] == "settled"
        assert payload["reason"] is None
        assert payload["occurred_at"].endswith("+00:00")

        record = (await api.get("/collections/collection-1")).json()
        assert record["amount_cents"] == 1299
        assert record["currency"] == "EUR"
        assert record["due_date"] == "2026-07-26"
        assert record["outcome"] == "settled"
        assert record["reason"] is None
        assert record["notifications"][0]["state"] == "delivered"


async def test_chargeback_submission_delivers_two_webhooks_in_order():
    captured = []

    async def handler(request):
        captured.append(request)
        return httpx.Response(200, request=request)

    api, webhook = await clients(handler)
    async with api, webhook:
        response = await api.post(
            "/collections",
            json=submission(iban="BE68539007547096"),
        )
        assert response.status_code == 202
        await wait_for_requests(captured, 2)

    payloads = [json.loads(request.content) for request in captured]
    assert [
        (payload["notification_id"], payload["outcome"], payload["reason"])
        for payload in payloads
    ] == [
        ("collection-1:1", "settled", None),
        ("collection-1:2", "charged_back", "MD06"),
    ]


async def test_failed_submission_delivers_reason():
    captured = []

    async def handler(request):
        captured.append(request)
        return httpx.Response(200, request=request)

    api, webhook = await clients(handler)
    async with api, webhook:
        response = await api.post(
            "/collections",
            json=submission(iban="BE68539007547099"),
        )
        assert response.status_code == 202
        await wait_for_requests(captured, 1)

    payload = json.loads(captured[0].content)
    assert payload["outcome"] == "failed"
    assert payload["reason"] == "AM04"


async def test_duplicate_submission_does_not_reschedule():
    captured = []

    async def handler(request):
        captured.append(request)
        return httpx.Response(200, request=request)

    api, webhook = await clients(handler)
    async with api, webhook:
        first = await api.post("/collections", json=submission())
        duplicate = await api.post("/collections", json=submission())
        assert first.status_code == 202
        assert duplicate.status_code == 200
        assert duplicate.json() == {
            "collection_id": "collection-1",
            "status": "accepted",
            "duplicate": True,
        }
        await wait_for_requests(captured, 1)
        await asyncio.sleep(0.05)
        assert len(captured) == 1


async def test_invalid_submissions_return_422():
    async def handler(request):
        return httpx.Response(200, request=request)

    api, webhook = await clients(handler)
    async with api, webhook:
        missing_mandate = submission()
        del missing_mandate["mandate_reference"]
        invalid_bodies = [
            missing_mandate,
            {**submission(), "amount_cents": 12.5},
            {**submission(), "amount_cents": 0},
            {**submission(), "currency": "eur"},
        ]
        for body in invalid_bodies:
            assert (await api.post("/collections", json=body)).status_code == 422


async def test_health_metrics_and_unknown_collection():
    async def handler(request):
        return httpx.Response(200, request=request)

    api, webhook = await clients(handler)
    async with api, webhook:
        health = await api.get("/health")
        assert health.status_code == 200
        assert health.json() == {
            "status": "ok",
            "bank_id": "bank-test",
            "scheme": "sepa_core",
        }

        metrics = await api.get("/metrics")
        assert metrics.status_code == 200
        assert "bank_collections_received_total" in metrics.text

        unknown = await api.get("/collections/unknown")
        assert unknown.status_code == 404
