import logging

import httpx
from prometheus_client import REGISTRY

import app.delivery as delivery_module
from app.delivery import deliver
from app.signing import verify


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


async def test_every_attempt_carries_its_own_fresh_signed_timestamp(monkeypatch):
    # R44: each retry re-signs at send time. With a clock that advances between
    # attempts, the timestamps differ, every signature verifies against ITS
    # timestamp, and none verifies against another attempt's.
    # Replace only delivery's module reference: httpx keeps its own `time`.
    class FakeTime:
        ticks = iter([1_800_000_000, 1_800_000_007, 1_800_000_021])

        def time(self):
            return next(self.ticks)

    monkeypatch.setattr(delivery_module, "time", FakeTime())
    requests = []

    async def handler(request):
        requests.append(request)
        status = 200 if len(requests) == 3 else 503
        return httpx.Response(status, request=request)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler)
    ) as client:
        result = await deliver(
            client,
            "http://sink/webhook",
            "secret",
            "bank-test",
            notification(),
            max_attempts=3,
            backoff_seconds=0,
        )

    assert result is True
    timestamps = [int(r.headers["X-Bank-Timestamp"]) for r in requests]
    assert timestamps == [1_800_000_000, 1_800_000_007, 1_800_000_021]
    for request, timestamp in zip(requests, timestamps):
        assert verify(request.content, "secret", request.headers["X-Bank-Signature"], timestamp)
    assert not verify(
        requests[1].content, "secret", requests[0].headers["X-Bank-Signature"], timestamps[1]
    )
