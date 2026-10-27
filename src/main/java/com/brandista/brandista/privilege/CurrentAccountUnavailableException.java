package com.brandista.brandista.privilege;

/** Infrastructure failure, never an authorization grant or invalid-credential verdict. */
public final class CurrentAccountUnavailableException extends org.springframework.security.core.AuthenticationException {
    public CurrentAccountUnavailableException(RuntimeException cause) {
        super("Current account verification is temporarily unavailable.", cause);
    }
}
