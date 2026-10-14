package com.brandista.brandista.cart;

import com.brandista.brandista.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "carts", uniqueConstraints = @UniqueConstraint(name = "uk_carts_customer", columnNames = "customer_id"))
public class Cart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_carts_customer"))
    private User customer;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Cart() { }

    public Cart(User customer) {
        this.customer = Objects.requireNonNull(customer, "cart customer is required");
    }

    public void addItem(CartItem item) {
        items.add(item);
        item.assignCart(this);
        touch();
    }

    public void removeItem(CartItem item) {
        items.remove(item);
        touch();
    }

    public void clearItems() {
        items.clear();
        touch();
    }

    public void touch() { updatedAt = LocalDateTime.now(Clock.systemUTC()); }

    @PrePersist
    void beforeInsert() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = LocalDateTime.now(Clock.systemUTC());
    }

    public Long getId() { return id; }
    public User getCustomer() { return customer; }
    public List<CartItem> getItems() { return items; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
