package com.brandista.brandista.brandapplication.service;

public class BrandApplicationReviewConflictException extends RuntimeException {
    public BrandApplicationReviewConflictException() { super("Only open applications can be reviewed."); }
}
