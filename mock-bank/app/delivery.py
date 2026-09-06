import asyncio
import json
import logging
import time

import httpx
from prometheus_client import Counter

from app.signing import sign


logger = logging.getLogger(__name__)

BANK_COLLECTIONS_RECEIVED = Counter(
    "bank_collections_received",
    "Collection submissions accepted by the mock bank",
)
BANK_WEBHOOK_DELIVERED = Counter(
    "bank_webhook_delivered",
    "Webhook notifications delivered successfully",
)
BANK_WEBHOOK_GIVEUPS = Counter(
    "bank_webhook_giveups",
    "Webhook notifications abandoned after bounded retries",
)


async def deliver(
    client: httpx.AsyncClient,
    url: str,
    secret: str,
    bank_id: str,
    notification: dict,
    max_attempts: int,
    backoff_seconds: float,
) -> bool:
    body = json.dumps(notification, separators=(",", ":")).encode("utf-8")

    for attempt in range(1, max_attempts + 1):
        # Re-sign every attempt with a fresh timestamp (R44): the receiver's
        # acceptance window then only has to cover clock skew and transit, not
        # the whole retry envelope, so a legitimately late redelivery is never
        # mistaken for a replay.
        timestamp = int(time.time())
        headers = {
            "Content-Type": "application/json",
            "X-Bank-Id": bank_id,
            "X-Bank-Timestamp": str(timestamp),
            "X-Bank-Signature": sign(body, secret, timestamp),
        }
        try:
            response = await client.post(url, content=body, headers=headers)
            if 200 <= response.status_code < 300:
                BANK_WEBHOOK_DELIVERED.inc()
                return True
        except httpx.HTTPError:
            pass

        if attempt < max_attempts:
            await asyncio.sleep(backoff_seconds * 2 ** (attempt - 1))

    logger.error(
        "bank webhook delivery gave up bank_id=%s notification_id=%s "
        "collection_id=%s url=%s attempts=%s",
        bank_id,
        notification.get("notification_id"),
        notification.get("collection_id"),
        url,
        max_attempts,
    )
    BANK_WEBHOOK_GIVEUPS.inc()
    return False
