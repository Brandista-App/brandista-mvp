package com.brandista.brandista.cart.service;

import com.brandista.brandista.cart.api.CartResponse;

public record CartMutationResult(CartResponse cart, boolean itemCreated) { }
