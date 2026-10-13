package com.brandista.brandista.cart.service;

public class CartProductUnavailableException extends RuntimeException {
    public CartProductUnavailableException() {
        super("Product is not currently available to add to the cart.");
    }
}
