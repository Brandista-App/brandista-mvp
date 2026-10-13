package com.brandista.brandista.product.service;

public class InactiveProductReferenceException extends RuntimeException {
    public InactiveProductReferenceException() {
        super("Products require active brands and categories.");
    }
}
