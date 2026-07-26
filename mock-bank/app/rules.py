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


def outcome_for(iban: str) -> tuple[str, str | None]:
    return RULE_SUFFIXES.get(iban[-2:], ("settled", None))


def notification_plan(collection_id: str, iban: str) -> list[dict]:
    del collection_id
    outcome, reason = outcome_for(iban)
    if outcome == "failed":
        return [{"seq": 1, "outcome": "failed", "reason": reason}]
    if outcome == "settled_then_chargeback":
        return [
            {"seq": 1, "outcome": "settled", "reason": None},
            {"seq": 2, "outcome": "charged_back", "reason": "MD06"},
        ]
    return [{"seq": 1, "outcome": "settled", "reason": None}]


def card_verdict_for(token: str) -> tuple[str, str | None]:
    return CARD_RULE_SUFFIXES.get(token[-2:], ("authorized", None))


def card_notification_plan(token: str) -> list[dict]:
    verdict, reason = card_verdict_for(token)
    if verdict == "declined":
        return []
    if verdict == "authorized_then_chargeback":
        return [
            {"seq": 1, "outcome": "settled", "reason": None},
            {"seq": 2, "outcome": "charged_back", "reason": reason},
        ]
    return [{"seq": 1, "outcome": "settled", "reason": None}]
