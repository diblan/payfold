import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    bank_id: str
    scheme: str
    webhook_url: str
    webhook_secret: str
    settlement_delay_seconds: float
    chargeback_lag_seconds: float
    retry_max_attempts: int
    retry_backoff_seconds: float


def load_settings() -> Settings:
    settings = Settings(
        bank_id=os.environ.get("BANK_ID", "bank-a"),
        scheme=os.environ.get("BANK_SCHEME", "sepa_core"),
        webhook_url=os.environ.get(
            "BANK_WEBHOOK_URL",
            "http://renewal-consumer:8080/webhooks/bank/bank-a",
        ),
        webhook_secret=os.environ.get(
            "BANK_WEBHOOK_SECRET", "payfold-dev-secret"
        ),
        settlement_delay_seconds=float(
            os.environ.get("BANK_SETTLEMENT_DELAY_SECONDS", "2.0")
        ),
        chargeback_lag_seconds=float(
            os.environ.get("BANK_CHARGEBACK_LAG_SECONDS", "5.0")
        ),
        retry_max_attempts=int(
            os.environ.get("BANK_WEBHOOK_RETRY_MAX_ATTEMPTS", "5")
        ),
        retry_backoff_seconds=float(
            os.environ.get("BANK_WEBHOOK_RETRY_BACKOFF_SECONDS", "0.5")
        ),
    )
    # BANK_SCHEME is the extension seam for the card scheme introduced in R23f.
    if settings.scheme != "sepa_core":
        raise ValueError(f"unsupported bank scheme: {settings.scheme}")
    return settings
