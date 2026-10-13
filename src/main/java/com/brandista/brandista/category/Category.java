package com.brandista.brandista.category;

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
        name = "categories",
        uniqueConstraints = @UniqueConstraint(name = "uk_categories_slug", columnNames = "slug"),
        indexes = @Index(name = "idx_categories_status_name", columnList = "status, name"))
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 160)
    private String slug;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CategoryStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Category() {
        // Required by JPA.
    }

    public Category(String name, String slug, String description) {
        this.name = name;
        this.slug = CategorySlugNormalizer.normalize(slug);
        this.description = description;
        this.status = CategoryStatus.ACTIVE;
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
        slug = CategorySlugNormalizer.normalize(slug);
        if (name == null || name.isBlank() || name.length() > 150) {
            throw new IllegalArgumentException("category name must contain 1 to 150 characters");
        }
        if (slug == null || slug.isBlank() || slug.length() > 160) {
            throw new IllegalArgumentException("category slug must contain 1 to 160 characters");
        }
        if (description != null && description.length() > 2000) {
            throw new IllegalArgumentException("category description must not exceed 2000 characters");
        }
        status = Objects.requireNonNull(status, "category status is required");
    }

    public void updateDetails(String name, String slug, String description, CategoryStatus status) {
        this.name = name;
        this.slug = CategorySlugNormalizer.normalize(slug);
        this.description = description;
        this.status = Objects.requireNonNull(status, "category status is required");
    }

    public void deactivate() {
        status = CategoryStatus.INACTIVE;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public CategoryStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
