package com.brandista.brandista.product.service;

public class DuplicateProductSlugException extends RuntimeException {
    public DuplicateProductSlugException() {
        super("A product with this slug already exists.");
    }
}
