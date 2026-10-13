package com.brandista.brandista.cart.service;

public class CartQuantityException extends RuntimeException {
    public CartQuantityException() { super("Cart quantity must be a positive integer within the supported range."); }
}
