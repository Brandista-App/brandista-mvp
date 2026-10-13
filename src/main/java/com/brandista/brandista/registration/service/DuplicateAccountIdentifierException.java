package com.brandista.brandista.registration.service;

public class DuplicateAccountIdentifierException extends RuntimeException {

    public DuplicateAccountIdentifierException() {
        super("Account identifier is already registered.");
    }
}
