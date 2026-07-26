import logging

import httpx
from prometheus_client import REGISTRY

from app.delivery import deliver


def notification():
    return {
        "schema_version": 1,
        "bank_id": "bank-test",
        "notification_id": "collection-1:1",
        "collection_id": "collection-1",
        "outcome": "settled",
        "reason": None,
        "occurred_at": "2026-07-26T12:00:00+00:00",
    }


async def test_retries_non_2xx_until_delivery():
    requests = []

    async def handler(request):
        requests.append(request)
        status = 200 if len(requests) == 3 else 500
        return httpx.Response(status, request=request)

    before = REGISTRY.get_sample_value("bank_webhook_delivered_total")
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler)
    ) as client:
        result = await deliver(
            client,
            "http://sink/webhook",
            "secret",
            "bank-test",
            notification(),
            max_attempts=5,
            backoff_seconds=0,
        )

    assert result is True
    assert len(requests) == 3
    assert (
        REGISTRY.get_sample_value("bank_webhook_delivered_total") - before == 1
    )


async def test_gives_up_after_bounded_attempts(caplog):
    requests = []

    async def handler(request):
        requests.append(request)
        return httpx.Response(500, request=request)

    before = REGISTRY.get_sample_value("bank_webhook_giveups_total")
    with caplog.at_level(logging.ERROR):
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(handler)
        ) as client:
            result = await deliver(
                client,
                "http://sink/webhook",
                "secret",
                "bank-test",
                notification(),
                max_attempts=4,
                backoff_seconds=0,
            )

    assert result is False
    assert len(requests) == 4
    assert REGISTRY.get_sample_value("bank_webhook_giveups_total") - before == 1
    assert any(
        record.levelno == logging.ERROR
        and "collection-1:1" in record.getMessage()
        for record in caplog.records
    )


async def test_retries_http_error():
    requests = []

    async def handler(request):
        requests.append(request)
        if len(requests) == 1:
            raise httpx.ConnectError("unreachable", request=request)
        return httpx.Response(200, request=request)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler)
    ) as client:
        result = await deliver(
            client,
            "http://sink/webhook",
            "secret",
            "bank-test",
            notification(),
            max_attempts=2,
            backoff_seconds=0,
        )

    assert result is True
    assert len(requests) == 2
