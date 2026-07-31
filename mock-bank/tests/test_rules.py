import pytest

from app.rules import (
    card_verdict_for,
    is_silent,
    notification_plan,
    outcome_for,
)


@pytest.mark.parametrize(
    ("suffix", "expected"),
    [
        ("99", ("failed", "AM04")),
        ("98", ("failed", "AC04")),
        ("97", ("failed", "MD01")),
        ("96", ("settled_then_chargeback", "MD06")),
    ],
)
def test_rule_suffixes(suffix, expected):
    assert outcome_for(f"BE685390075470{suffix}") == expected


def test_clean_iban_settles():
    assert outcome_for("BE68539007547034") == ("settled", None)


def test_silent_suffix_matches_iban_and_card_token_shapes():
    assert is_silent("BE68539007547094")
    assert is_silent("tok-0000000094")
    assert not is_silent("BE68539007547001")
    assert not is_silent("tok-0000000001")
    assert not is_silent("BE68539007547099")
    assert not is_silent("tok-0000000099")


def test_silent_suffix_still_classifies_normally():
    assert outcome_for("BE68539007547094") == ("settled", None)
    assert card_verdict_for("tok-0000000094") == ("authorized", None)


def test_notification_plan_for_settled_collection():
    assert notification_plan("collection-1", "BE68539007547034") == [
        {"seq": 1, "outcome": "settled", "reason": None}
    ]


@pytest.mark.parametrize(
    ("suffix", "reason"),
    [("99", "AM04"), ("98", "AC04"), ("97", "MD01")],
)
def test_notification_plan_for_failed_collection(suffix, reason):
    assert notification_plan("collection-1", f"BE685390075470{suffix}") == [
        {"seq": 1, "outcome": "failed", "reason": reason}
    ]


def test_notification_plan_for_chargeback_is_ordered():
    assert notification_plan("collection-1", "BE68539007547096") == [
        {"seq": 1, "outcome": "settled", "reason": None},
        {"seq": 2, "outcome": "charged_back", "reason": "MD06"},
    ]
