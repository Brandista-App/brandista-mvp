package com.brandista.brandista.user;

/** One database snapshot of the authority fields; never cached or derived from JWT claims. */
public record CurrentAuthorization(UserRole role, AccountStatus status, boolean superAdmin) { }
