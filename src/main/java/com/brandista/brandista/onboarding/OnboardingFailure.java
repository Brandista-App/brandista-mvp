package com.brandista.brandista.onboarding;

import org.springframework.http.HttpStatus;

public class OnboardingFailure extends RuntimeException {
    private final HttpStatus status;
    public OnboardingFailure(HttpStatus status,String message) { super(message); this.status=status; }
    public HttpStatus status() { return status; }
}
