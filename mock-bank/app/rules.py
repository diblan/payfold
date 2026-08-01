import re


ATTEMPT_PATTERN = re.compile(r"\|a(\d+)$")

RULE_SUFFIXES = {
    "99": ("failed", "AM04"),  # insufficient funds
    "98": ("failed", "AC04"),  # account closed
    "97": ("failed", "MD01"),  # no valid mandate
    "96": ("settled_then_chargeback", "MD06"),  # payer objection after settlement
}

CARD_RULE_SUFFIXES = {
    "99": ("declined", "insufficient_funds"),
    "98": ("declined", "do_not_honor"),
    "96": ("authorized_then_chargeback", "fraud_dispute"),
}

SILENT_SUFFIXES = {"94"}


def is_silent(identifier: str) -> bool:
    return identifier[-2:] in SILENT_SUFFIXES


def attempt_of(collection_id: str) -> int:
    match = ATTEMPT_PATTERN.search(collection_id)
    return int(match.group(1)) if match else 1


def outcome_for(
    iban: str, collection_id: str = ""
) -> tuple[str, str | None]:
    if iban[-2:] == "95":
        # The attempt lives in the id so the verdict survives restart amnesia
        # and worker hops (D20).
        return (
            ("failed", "AM04")
            if attempt_of(collection_id) == 1
            else ("settled", None)
        )
    return RULE_SUFFIXES.get(iban[-2:], ("settled", None))


def notification_plan(collection_id: str, iban: str) -> list[dict]:
    outcome, reason = outcome_for(iban, collection_id)
    if outcome == "failed":
        return [{"seq": 1, "outcome": "failed", "reason": reason}]
    if outcome == "settled_then_chargeback":
        return [
            {"seq": 1, "outcome": "settled", "reason": None},
            {"seq": 2, "outcome": "charged_back", "reason": "MD06"},
        ]
    return [{"seq": 1, "outcome": "settled", "reason": None}]


def card_verdict_for(
    token: str, collection_id: str = ""
) -> tuple[str, str | None]:
    if token[-2:] == "95":
        return (
            ("declined", "insufficient_funds")
            if attempt_of(collection_id) == 1
            else ("authorized", None)
        )
    return CARD_RULE_SUFFIXES.get(token[-2:], ("authorized", None))


def card_notification_plan(token: str, collection_id: str = "") -> list[dict]:
    verdict, reason = card_verdict_for(token, collection_id)
    if verdict == "declined":
        return []
    if verdict == "authorized_then_chargeback":
        return [
            {"seq": 1, "outcome": "settled", "reason": None},
            {"seq": 2, "outcome": "charged_back", "reason": reason},
        ]
    return [{"seq": 1, "outcome": "settled", "reason": None}]
