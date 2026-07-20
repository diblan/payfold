package com.blanchaert.billing.consumer.service;

public class InvalidRenewalMessageException extends RuntimeException {
    public InvalidRenewalMessageException(String message) {
        super(message);
    }
}
