package com.brandista.brandista.product.service;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException() {
        super("Product was not found.");
    }
}
