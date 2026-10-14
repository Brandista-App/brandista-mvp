package com.brandista.brandista.cart.api;

import java.math.BigDecimal;

public record CartItemResponse(
        Long itemId,
        Long productId,
        String productName,
        String brandName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineSubtotal,
        boolean available) { }
