package com.brandista.brandista.product.api;

import java.util.List;

public record PublicProductPageResponse(
        List<PublicProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
