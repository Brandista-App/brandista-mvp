package com.brandista.brandista.brandapplication.service;

public class DuplicateOpenBrandApplicationException extends RuntimeException {
    public DuplicateOpenBrandApplicationException() { super("An open application already exists for this contact email."); }
}
