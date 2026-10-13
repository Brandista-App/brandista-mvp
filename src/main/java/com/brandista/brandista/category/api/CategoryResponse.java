package com.brandista.brandista.category.api;

import com.brandista.brandista.category.CategoryStatus;

import java.time.LocalDateTime;

public record CategoryResponse(
        Long id,
        String name,
        String slug,
        String description,
        CategoryStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
