package com.brandista.brandista.cart.service;

public class CartNotFoundException extends RuntimeException {
    public CartNotFoundException() { super("Cart item was not found."); }
}
