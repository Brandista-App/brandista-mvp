package com.brandista.brandista.category.api;

import java.util.List;

public record CategoryPageResponse(
        List<CategoryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
