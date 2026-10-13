package com.brandista.brandista.cart.api;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(Long id, List<CartItemResponse> items, BigDecimal subtotal, String currency) { }
