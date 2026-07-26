import hashlib
import hmac

from app.signing import sign, verify


def test_sign_and_verify_round_trip_matches_independent_digest():
    body = b'{"collection_id":"collection-1"}'
    secret = "test-secret"
    expected = "sha256=" + hmac.new(
        secret.encode("utf-8"), body, hashlib.sha256
    ).hexdigest()

    assert sign(body, secret) == expected
    assert verify(body, secret, expected)


def test_verify_rejects_tampering_wrong_secret_and_malformed_header():
    body = b'{"collection_id":"collection-1"}'
    signature = sign(body, "test-secret")

    assert not verify(body + b" ", "test-secret", signature)
    assert not verify(body, "wrong-secret", signature)
    assert not verify(body, "test-secret", "not-a-signature")
