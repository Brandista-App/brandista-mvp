package com.brandista.brandista.cart;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    Optional<CartItem> findByIdAndCart_Id(Long id, Long cartId);
    Optional<CartItem> findByCart_IdAndProduct_Id(Long cartId, Long productId);
}
