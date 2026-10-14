package com.brandista.brandista.cart.api;

import com.brandista.brandista.cart.service.CartService;
import com.brandista.brandista.cart.service.CartMutationResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/cart")
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerCartController {

    private final CartService cartService;

    public CustomerCartController(CartService cartService) { this.cartService = cartService; }

    @GetMapping
    public CartResponse get(@AuthenticationPrincipal Jwt principal) {
        return cartService.get(principal.getSubject());
    }

    @PostMapping("/items")
    public ResponseEntity<CartResponse> add(@AuthenticationPrincipal Jwt principal,
                                            @Valid @RequestBody AddCartItemRequest request) {
        CartMutationResult result = cartService.add(principal.getSubject(), request);
        return ResponseEntity.status(result.itemCreated() ? HttpStatus.CREATED : HttpStatus.OK).body(result.cart());
    }

    @PatchMapping("/items/{itemId}")
    public CartResponse update(@AuthenticationPrincipal Jwt principal, @PathVariable Long itemId,
                               @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.update(principal.getSubject(), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt principal, @PathVariable Long itemId) {
        cartService.remove(principal.getSubject(), itemId);
    }

    @DeleteMapping("/items")
    public CartResponse clear(@AuthenticationPrincipal Jwt principal) {
        return cartService.clear(principal.getSubject());
    }
}
