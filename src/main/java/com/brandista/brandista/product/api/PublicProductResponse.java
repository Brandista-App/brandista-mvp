package com.brandista.brandista.product.api;

import com.brandista.brandista.product.ProductAvailability;

import java.math.BigDecimal;

public record PublicProductResponse(
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
        int stockQuantity) {
}
