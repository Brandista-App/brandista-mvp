package com.brandista.brandista.brand.api;

import com.brandista.brandista.brand.BrandStatus;

import java.time.LocalDateTime;

public record BrandResponse(
        Long id,
        String name,
        String slug,
        String description,
        String logoReference,
        BrandStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
