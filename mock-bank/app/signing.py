import hashlib
import hmac


def signed_bytes(body: bytes, timestamp: int) -> bytes:
    # The signature binds the body to a moment (R44): "<unix seconds>." is
    # prepended to the exact body bytes, and the receiver checks the timestamp
    # against a bounded acceptance window before touching its inbox.
    return f"{timestamp}.".encode("utf-8") + body


def sign(body: bytes, secret: str, timestamp: int) -> str:
    digest = hmac.new(
        secret.encode("utf-8"), signed_bytes(body, timestamp), hashlib.sha256
    ).hexdigest()
    return f"sha256={digest}"


def verify(body: bytes, secret: str, signature_header: str, timestamp: int) -> bool:
    return hmac.compare_digest(sign(body, secret, timestamp), signature_header)
