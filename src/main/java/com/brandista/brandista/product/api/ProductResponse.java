package com.brandista.brandista.product.api;

import com.brandista.brandista.product.ProductAvailability;
import com.brandista.brandista.product.ProductPublicationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductResponse(
        Long id,
        String name,
        String slug,
        String description,
        Long brandId,
        String brandName,
        Long categoryId,
        String categoryName,
        BigDecimal price,
        String currency,
        ProductAvailability availability,
        ProductPublicationStatus publicationStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
