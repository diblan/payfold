package com.blanchaert.billing.consumer.service;

public class InvalidSettlementMessageException extends RuntimeException {
    public InvalidSettlementMessageException(String message) {
        super(message);
    }
}
