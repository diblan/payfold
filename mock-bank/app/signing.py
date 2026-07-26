import hashlib
import hmac


def sign(body: bytes, secret: str) -> str:
    digest = hmac.new(
        secret.encode("utf-8"), body, hashlib.sha256
    ).hexdigest()
    return f"sha256={digest}"


def verify(body: bytes, secret: str, signature_header: str) -> bool:
    return hmac.compare_digest(sign(body, secret), signature_header)
