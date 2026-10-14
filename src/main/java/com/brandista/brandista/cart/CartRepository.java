package com.brandista.brandista.cart;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    @EntityGraph(attributePaths = {"items", "items.product", "items.product.brand", "items.product.category"})
    @Query("select distinct cart from Cart cart where cart.customer.id = :customerId")
    Optional<Cart> findAggregateByCustomerId(@Param("customerId") Long customerId);
}
