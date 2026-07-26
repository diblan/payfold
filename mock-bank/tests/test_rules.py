import pytest

from app.rules import notification_plan, outcome_for


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
