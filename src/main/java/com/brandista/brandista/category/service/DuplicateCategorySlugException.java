package com.brandista.brandista.category.service;

public class DuplicateCategorySlugException extends RuntimeException {
    public DuplicateCategorySlugException() {
        super("A category with this slug already exists.");
    }
}
