package com.brandista.brandista.auth.api;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Account account) {

    public record Account(Long id, String email, String role) {
    }
}
