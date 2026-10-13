package com.brandista.brandista.category.service;

public class CategoryNotFoundException extends RuntimeException {
    public CategoryNotFoundException() {
        super("Category was not found.");
    }
}
