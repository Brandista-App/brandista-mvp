package com.brandista.brandista.brandapplication.service;

public class BrandApplicationNotFoundException extends RuntimeException {
    public BrandApplicationNotFoundException() { super("Brand application was not found."); }
}
