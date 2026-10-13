package com.brandista.brandista.brand.service;

public class BrandNotFoundException extends RuntimeException {

    public BrandNotFoundException() {
        super("Brand was not found.");
    }
}
