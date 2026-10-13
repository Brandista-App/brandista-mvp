package com.brandista.brandista.cart.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) { }
