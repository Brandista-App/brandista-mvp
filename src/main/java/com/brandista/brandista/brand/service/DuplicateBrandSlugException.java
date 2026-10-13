package com.brandista.brandista.brand.service;

public class DuplicateBrandSlugException extends RuntimeException {

    public DuplicateBrandSlugException() {
        super("A brand with this slug already exists.");
    }
}
