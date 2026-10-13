package com.brandista.brandista.address.service;

public class AddressNotFoundException extends RuntimeException {

    public AddressNotFoundException() {
        super("Address was not found.");
    }
}
