package com.brandista.brandista.product.service;

public class DeactivatedProductException extends RuntimeException {
    public DeactivatedProductException() {
        super("A deactivated product cannot be edited or published.");
    }
}
