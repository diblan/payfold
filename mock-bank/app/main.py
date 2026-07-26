import asyncio
from contextlib import asynccontextmanager
from datetime import date, datetime, timezone

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel, Field, ValidationError

from app.config import Settings, load_settings
from app.delivery import BANK_COLLECTIONS_RECEIVED, deliver
from app.rules import (
    card_notification_plan,
    card_verdict_for,
    notification_plan,
    outcome_for,
)


class CollectionSubmission(BaseModel):
    collection_id: str = Field(min_length=1)
    amount_cents: int = Field(strict=True, gt=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    debtor_iban: str = Field(min_length=8)
    mandate_reference: str = Field(min_length=1)
    due_date: date


class CardSubmission(BaseModel):
    collection_id: str = Field(min_length=1)
    amount_cents: int = Field(strict=True, gt=0)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    card_token: str = Field(min_length=8)
    due_date: date


def create_app(
    settings: Settings,
    http_client: httpx.AsyncClient | None = None,
) -> FastAPI:
    client = http_client or httpx.AsyncClient()
    owns_client = http_client is None
    records: dict[str, dict] = {}
    tasks: set[asyncio.Task] = set()

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        try:
            yield
        finally:
            pending = [task for task in tasks if not task.done()]
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
            if owns_client:
                await client.aclose()

    application = FastAPI(lifespan=lifespan)
    # A compose instance uses one uvicorn worker, so this in-memory store needs no lock.
    application.state.records = records
    application.state.tasks = tasks

    async def send_notification(
        record: dict,
        notification_record: dict,
    ) -> None:
        delay = settings.settlement_delay_seconds
        if notification_record["seq"] == 2:
            delay += settings.chargeback_lag_seconds
        await asyncio.sleep(delay)

        notification = {
            "schema_version": 1,
            "bank_id": settings.bank_id,
            "notification_id": notification_record["notification_id"],
            "collection_id": record["collection_id"],
            "outcome": notification_record["outcome"],
            "reason": notification_record["reason"],
            "occurred_at": datetime.now(timezone.utc).isoformat(),
        }
        delivered = await deliver(
            client=client,
            url=settings.webhook_url,
            secret=settings.webhook_secret,
            bank_id=settings.bank_id,
            notification=notification,
            max_attempts=settings.retry_max_attempts,
            backoff_seconds=settings.retry_backoff_seconds,
        )
        notification_record["state"] = "delivered" if delivered else "gave_up"

    def schedule(record: dict, notification_record: dict) -> None:
        task = asyncio.create_task(send_notification(record, notification_record))
        tasks.add(task)
        task.add_done_callback(tasks.discard)

    @application.post("/collections", status_code=202)
    async def submit_collection(request: Request):
        model = CardSubmission if settings.scheme == "card" else CollectionSubmission
        try:
            submission = model.model_validate(await request.json())
        except (ValidationError, ValueError) as exception:
            raise HTTPException(status_code=422, detail=str(exception)) from exception

        existing = records.get(submission.collection_id)
        if existing is not None:
            content = {
                "collection_id": submission.collection_id,
                "status": existing["response_status"],
                "duplicate": True,
            }
            if existing["response_reason"] is not None:
                content["reason"] = existing["response_reason"]
            return JSONResponse(
                content=content,
                status_code=200,
            )

        if settings.scheme == "card":
            classified_outcome, classified_reason = card_verdict_for(
                submission.card_token
            )
            response_status = (
                "declined" if classified_outcome == "declined" else "authorized"
            )
            plan = card_notification_plan(submission.card_token)
        else:
            classified_outcome, classified_reason = outcome_for(
                submission.debtor_iban
            )
            response_status = "accepted"
            plan = notification_plan(
                submission.collection_id, submission.debtor_iban
            )

        notifications = [
            {
                **planned,
                "notification_id": f"{submission.collection_id}:{planned['seq']}",
                "state": "scheduled",
            }
            for planned in plan
        ]
        record = {
            **submission.model_dump(mode="json"),
            "outcome": classified_outcome,
            "reason": classified_reason,
            "response_status": response_status,
            "response_reason": (
                classified_reason if response_status == "declined" else None
            ),
            "notifications": notifications,
        }
        records[submission.collection_id] = record
        BANK_COLLECTIONS_RECEIVED.inc()
        for notification_record in notifications:
            schedule(record, notification_record)

        content = {
            "collection_id": submission.collection_id,
            "status": response_status,
            "duplicate": False,
        }
        if response_status == "declined":
            content["reason"] = classified_reason
        return JSONResponse(
            content=content,
            status_code=200 if response_status == "declined" else 202,
        )

    @application.get("/collections/{collection_id}")
    async def get_collection(collection_id: str) -> dict:
        record = records.get(collection_id)
        if record is None:
            raise HTTPException(status_code=404, detail="collection not found")
        return record

    @application.get("/health")
    async def health() -> dict:
        return {
            "status": "ok",
            "bank_id": settings.bank_id,
            "scheme": settings.scheme,
        }

    @application.get("/metrics")
    async def metrics() -> Response:
        return Response(
            content=generate_latest(),
            media_type=CONTENT_TYPE_LATEST,
        )

    return application


settings = load_settings()
app = create_app(settings)
