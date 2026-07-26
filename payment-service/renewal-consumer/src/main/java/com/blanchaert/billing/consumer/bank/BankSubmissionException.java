package com.blanchaert.billing.consumer.bank;

public class BankSubmissionException extends RuntimeException {
    public BankSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
