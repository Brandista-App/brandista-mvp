package com.brandista.brandista.brand;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "brands",
        uniqueConstraints = @UniqueConstraint(name = "uk_brands_slug", columnNames = "slug"),
        indexes = @Index(name = "idx_brands_status_name", columnList = "status, name"))
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 160)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "logo_reference", length = 2048)
    private String logoReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BrandStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Brand() {
        // Required by JPA.
    }

    public Brand(String name, String slug, String description, String logoReference) {
        this.name = name;
        this.slug = BrandSlugNormalizer.normalize(slug);
        this.description = description;
        this.logoReference = logoReference;
        this.status = BrandStatus.ACTIVE;
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

    private void normalizeAndValidate() {
        name = name == null ? null : name.trim();
        slug = BrandSlugNormalizer.normalize(slug);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("brand name is required");
        }
        if (name.length() > 150) {
            throw new IllegalArgumentException("brand name must not exceed 150 characters");
        }
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("brand slug is required");
        }
        if (slug.length() > 160) {
            throw new IllegalArgumentException("brand slug must not exceed 160 characters");
        }
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException("brand description must not exceed 2000 characters");
        }
        if (logoReference != null && logoReference.length() > 2048) {
            throw new IllegalArgumentException("brand logo reference must not exceed 2048 characters");
        }
        status = Objects.requireNonNull(status, "brand status is required");
    }

    public void updateDetails(String name, String slug, String description, String logoReference, BrandStatus status) {
        this.name = name;
        this.slug = BrandSlugNormalizer.normalize(slug);
        this.description = description;
        this.logoReference = logoReference;
        this.status = Objects.requireNonNull(status, "brand status is required");
    }

    public void deactivate() {
        status = BrandStatus.INACTIVE;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public String getLogoReference() { return logoReference; }
    public BrandStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
