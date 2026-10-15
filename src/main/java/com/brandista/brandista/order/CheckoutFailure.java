package com.brandista.brandista.order;
import org.springframework.http.HttpStatus;
public class CheckoutFailure extends RuntimeException {
    private final HttpStatus status;
    public CheckoutFailure(HttpStatus status,String message) { super(message); this.status=status; }
    public HttpStatus status() { return status; }
}
