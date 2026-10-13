package com.brandista.brandista.product.service;

public class ProductReferenceNotFoundException extends RuntimeException {
    public ProductReferenceNotFoundException() {
        super("The selected brand or category was not found.");
    }
}
