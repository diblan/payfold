package com.blanchaert.billing.consumer.psp;

public record PspChargeOutcome(boolean succeeded, String reason) {
    public static PspChargeOutcome success() { return new PspChargeOutcome(true, null); }
    public static PspChargeOutcome failure(String reason) { return new PspChargeOutcome(false, reason); }
}
