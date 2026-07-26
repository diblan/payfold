import asyncio
import json

import httpx

from app.config import Settings
from app.main import create_app
from app.rules import card_notification_plan, card_verdict_for


def settings(scheme="card"):
    return Settings(
        bank_id="cardnet",
        scheme=scheme,
        webhook_url="http://sink/webhook",
        webhook_secret="card-secret",
        settlement_delay_seconds=0.02,
        chargeback_lag_seconds=0.03,
        retry_max_attempts=3,
        retry_backoff_seconds=0,
    )


def submission(collection_id="card-1", token="tok-000000000001"):
    return {
        "collection_id": collection_id,
        "amount_cents": 1499,
        "currency": "EUR",
        "card_token": token,
        "due_date": "2026-07-26",
    }


async def wait_for_requests(requests, count):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + 2
    while len(requests) < count and loop.time() < deadline:
        await asyncio.sleep(0.02)
    assert len(requests) == count


async def clients(captured, scheme="card"):
    async def handler(request):
        captured.append(request)
        return httpx.Response(200, request=request)

    webhook_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    application = create_app(settings(scheme), webhook_client)
    api_client = httpx.AsyncClient(
        transport=httpx.ASGITransport(app=application),
        base_url="http://test",
    )
    return api_client, webhook_client


def test_card_verdict_and_notification_rules():
    assert card_verdict_for("tok-0000000099") == (
        "declined",
        "insufficient_funds",
    )
    assert card_verdict_for("tok-0000000098") == ("declined", "do_not_honor")
    assert card_verdict_for("tok-0000000096") == (
        "authorized_then_chargeback",
        "fraud_dispute",
    )
    assert card_verdict_for("tok-0000000001") == ("authorized", None)
    assert card_notification_plan("tok-0000000099") == []
    assert card_notification_plan("tok-0000000001") == [
        {"seq": 1, "outcome": "settled", "reason": None}
    ]


async def test_decline_is_a_synchronous_verdict_without_scheduling():
    captured = []
    api, webhook = await clients(captured)
    async with api, webhook:
        response = await api.post(
            "/collections", json=submission(token="tok-0000000099")
        )
        assert response.status_code == 200
        assert response.json() == {
            "collection_id": "card-1",
            "status": "declined",
            "reason": "insufficient_funds",
            "duplicate": False,
        }
        await asyncio.sleep(0.05)
        assert captured == []
        record = (await api.get("/collections/card-1")).json()
        assert record["outcome"] == "declined"
        assert record["notifications"] == []


async def test_authorized_card_delivers_one_settled_webhook():
    captured = []
    api, webhook = await clients(captured)
    async with api, webhook:
        response = await api.post("/collections", json=submission())
        assert response.status_code == 202
        assert response.json() == {
            "collection_id": "card-1",
            "status": "authorized",
            "duplicate": False,
        }
        await wait_for_requests(captured, 1)

    payload = json.loads(captured[0].content)
    assert (
        payload["notification_id"],
        payload["outcome"],
        payload["reason"],
    ) == ("card-1:1", "settled", None)


async def test_chargeback_card_delivers_settlement_then_dispute_in_order():
    captured = []
    api, webhook = await clients(captured)
    async with api, webhook:
        response = await api.post(
            "/collections", json=submission(token="tok-0000000096")
        )
        assert response.status_code == 202
        assert response.json()["status"] == "authorized"
        await wait_for_requests(captured, 2)

    assert [
        (
            payload["notification_id"],
            payload["outcome"],
            payload["reason"],
        )
        for payload in (json.loads(request.content) for request in captured)
    ] == [
        ("card-1:1", "settled", None),
        ("card-1:2", "charged_back", "fraud_dispute"),
    ]


async def test_duplicate_returns_the_stored_verdict_without_extra_delivery():
    captured = []
    api, webhook = await clients(captured)
    async with api, webhook:
        first = await api.post("/collections", json=submission())
        duplicate = await api.post(
            "/collections",
            json=submission(token="tok-ignored-on-duplicate-99"),
        )
        assert first.status_code == 202
        assert duplicate.status_code == 200
        assert duplicate.json() == {
            "collection_id": "card-1",
            "status": "authorized",
            "duplicate": True,
        }
        await wait_for_requests(captured, 1)
        await asyncio.sleep(0.05)
        assert len(captured) == 1


async def test_sepa_core_instance_rejects_card_submission_body():
    captured = []
    api, webhook = await clients(captured, scheme="sepa_core")
    async with api, webhook:
        response = await api.post("/collections", json=submission())
        assert response.status_code == 422
        assert "debtor_iban" in response.text
        assert "mandate_reference" in response.text
