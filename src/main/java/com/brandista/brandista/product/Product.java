package com.brandista.brandista.product;

import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.category.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
        name = "products",
        uniqueConstraints = @UniqueConstraint(name = "uk_products_slug", columnNames = "slug"),
        indexes = {
                @Index(name = "idx_products_public_name", columnList = "publication_status, name"),
                @Index(name = "idx_products_public_price", columnList = "publication_status, price, id"),
                @Index(name = "idx_products_public_brand", columnList = "publication_status, brand_id"),
                @Index(name = "idx_products_public_category", columnList = "publication_status, category_id"),
                @Index(name = "idx_products_brand_id", columnList = "brand_id"),
                @Index(name = "idx_products_category_id", columnList = "category_id")
        })
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "slug", nullable = false, length = 160)
    private String slug;

    @Column(name = "description", length = 4000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false, foreignKey = @ForeignKey(name = "fk_products_brand"))
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false, foreignKey = @ForeignKey(name = "fk_products_category"))
    private Category category;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity;

    public int getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(int value) {
        if (value < 0) throw new IllegalArgumentException("Stock cannot be negative");
        stockQuantity = value;
    }
    public void consumeStock(int quantity) {
        if (quantity < 1 || quantity > stockQuantity) throw new IllegalStateException("Insufficient stock");
        stockQuantity -= quantity;
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "availability", nullable = false, length = 16)
    private ProductAvailability availability;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 16)
    private ProductPublicationStatus publicationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Product() {
        // Required by JPA.
    }

    public Product(String name, String slug, String description, Brand brand, Category category,
                   BigDecimal price, ProductAvailability availability) {
        this.name = name;
        this.slug = ProductSlugNormalizer.normalize(slug);
        this.description = description;
        this.brand = brand;
        this.category = category;
        this.price = price;
        this.availability = availability;
        this.publicationStatus = ProductPublicationStatus.DRAFT;
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
        slug = ProductSlugNormalizer.normalize(slug);
        if (name == null || name.isBlank() || name.length() > 150) {
            throw new IllegalArgumentException("product name must contain 1 to 150 characters");
        }
        if (slug == null || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 160) {
            throw new IllegalArgumentException("product slug is invalid");
        }
        if (description != null && description.length() > 4000) {
            throw new IllegalArgumentException("product description must not exceed 4000 characters");
        }
        Objects.requireNonNull(brand, "product brand is required");
        Objects.requireNonNull(category, "product category is required");
        validatePrice(price);
        availability = Objects.requireNonNull(availability, "product availability is required");
        publicationStatus = Objects.requireNonNull(publicationStatus, "product publication status is required");
    }

    private static void validatePrice(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 2 || value.precision() - value.scale() > 10) {
            throw new IllegalArgumentException("product price must be non-negative with at most 10 integer and 2 fractional digits");
        }
    }

    public void updateDetails(String name, String slug, String description, Brand brand, Category category,
                              BigDecimal price, ProductAvailability availability) {
        if (publicationStatus == ProductPublicationStatus.DEACTIVATED) {
            throw new IllegalStateException("deactivated products cannot be edited");
        }
        this.name = name;
        this.slug = ProductSlugNormalizer.normalize(slug);
        this.description = description;
        this.brand = brand;
        this.category = category;
        this.price = price;
        this.availability = availability;
    }

    public void publish() {
        if (publicationStatus == ProductPublicationStatus.DEACTIVATED) {
            throw new IllegalStateException("deactivated products cannot be published");
        }
        publicationStatus = ProductPublicationStatus.PUBLISHED;
    }

    public void unpublish() {
        if (publicationStatus == ProductPublicationStatus.DEACTIVATED) {
            throw new IllegalStateException("deactivated products cannot be edited");
        }
        publicationStatus = ProductPublicationStatus.UNPUBLISHED;
    }

    public void deactivate() {
        publicationStatus = ProductPublicationStatus.DEACTIVATED;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public Brand getBrand() { return brand; }
    public Category getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public ProductAvailability getAvailability() { return availability; }
    public ProductPublicationStatus getPublicationStatus() { return publicationStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
