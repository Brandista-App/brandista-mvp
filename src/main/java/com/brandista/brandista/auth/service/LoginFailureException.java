package com.brandista.brandista.auth.service;

public class LoginFailureException extends RuntimeException {

    public LoginFailureException() {
        super("Invalid email or password.");
    }
}
