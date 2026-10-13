package com.brandista.brandista.api;

import java.util.List;

public record ApiErrorResponse(
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }
}
