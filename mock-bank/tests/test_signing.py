import hashlib
import hmac

from app.signing import sign, signed_bytes, verify


def test_sign_and_verify_round_trip_matches_independent_digest():
    body = b'{"collection_id":"collection-1"}'
    secret = "test-secret"
    timestamp = 1_800_000_000
    expected = "sha256=" + hmac.new(
        secret.encode("utf-8"), b"1800000000." + body, hashlib.sha256
    ).hexdigest()

    assert signed_bytes(body, timestamp) == b"1800000000." + body
    assert sign(body, secret, timestamp) == expected
    assert verify(body, secret, expected, timestamp)


def test_verify_rejects_tampering_wrong_secret_wrong_moment_and_malformed_header():
    body = b'{"collection_id":"collection-1"}'
    timestamp = 1_800_000_000
    signature = sign(body, "test-secret", timestamp)

    assert not verify(body + b" ", "test-secret", signature, timestamp)
    assert not verify(body, "wrong-secret", signature, timestamp)
    # The timestamp is inside the signed bytes: moving it invalidates the
    # signature, so a captured signature cannot be re-dated (R44).
    assert not verify(body, "test-secret", signature, timestamp + 1)
    assert not verify(body, "test-secret", "not-a-signature", timestamp)
