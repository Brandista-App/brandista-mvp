package com.brandista.brandista.address;

import com.brandista.brandista.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "customer_addresses", indexes = @Index(
        name = "idx_customer_addresses_customer_created", columnList = "customer_id, created_at, id"))
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_customer_addresses_user"))
    private User customer;

    @Column(name = "recipient_name", nullable = false, length = 120)
    private String recipientName;

    @Column(name = "phone_number", nullable = false, length = 25)
    private String phoneNumber;

    @Column(name = "address_line1", nullable = false, length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "province", length = 100)
    private String province;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected CustomerAddress() {
        // Required by JPA.
    }

    public CustomerAddress(User customer, String recipientName, String phoneNumber, String addressLine1,
                           String addressLine2, String city, String province, String postalCode) {
        this.customer = Objects.requireNonNull(customer, "address customer is required");
        updateDetails(recipientName, phoneNumber, addressLine1, addressLine2, city, province, postalCode);
    }

    @PrePersist
    void beforeInsert() {
        normalizeAndValidate();
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        normalizeAndValidate();
        updatedAt = LocalDateTime.now(Clock.systemUTC());
    }

    public void updateDetails(String recipientName, String phoneNumber, String addressLine1,
                              String addressLine2, String city, String province, String postalCode) {
        this.recipientName = trimRequired(recipientName);
        this.phoneNumber = trimRequired(phoneNumber);
        this.addressLine1 = trimRequired(addressLine1);
        this.addressLine2 = trimOptional(addressLine2);
        this.city = trimRequired(city);
        this.province = trimOptional(province);
        this.postalCode = trimOptional(postalCode);
    }

    private void normalizeAndValidate() {
        recipientName = trimRequired(recipientName);
        phoneNumber = trimRequired(phoneNumber);
        addressLine1 = trimRequired(addressLine1);
        addressLine2 = trimOptional(addressLine2);
        city = trimRequired(city);
        province = trimOptional(province);
        postalCode = trimOptional(postalCode);
        Objects.requireNonNull(customer, "address customer is required");
    }

    private static String trimRequired(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimOptional(String value) {
        String trimmed = value == null ? null : value.trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    public Long getId() { return id; }
    public User getCustomer() { return customer; }
    public String getRecipientName() { return recipientName; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getAddressLine1() { return addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public String getCity() { return city; }
    public String getProvince() { return province; }
    public String getPostalCode() { return postalCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
