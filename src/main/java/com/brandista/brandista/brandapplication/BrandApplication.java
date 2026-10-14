package com.brandista.brandista.brandapplication;

import com.brandista.brandista.brand.Brand;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "brand_applications")
public class BrandApplication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_name", nullable = false, length = 150)
    private String brandName;

    @Column(name = "contact_name", nullable = false, length = 150)
    private String contactName;

    @Column(name = "contact_email", nullable = false, length = 254)
    private String contactEmail;

    @Column(length = 2048)
    private String website;

    @Column(length = 2000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BrandApplicationStatus status;

    @Column(name = "review_note", length = 2000)
    private String reviewNote;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected BrandApplication() { }

    public BrandApplication(String brandName, String contactName, String contactEmail, String website, String message) {
        this.brandName = brandName.trim();
        this.contactName = contactName.trim();
        this.contactEmail = contactEmail.trim().toLowerCase(java.util.Locale.ROOT);
        this.website = blankToNull(website);
        this.message = blankToNull(message);
        this.status = BrandApplicationStatus.SUBMITTED;
    }

    @PrePersist
    void beforeInsert() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() { updatedAt = LocalDateTime.now(Clock.systemUTC()); }

    public void review(BrandApplicationStatus decision, String note, Brand approvedBrand) {
        if (status != BrandApplicationStatus.SUBMITTED && status != BrandApplicationStatus.NEEDS_INFORMATION) {
            throw new IllegalStateException("only open brand applications can be reviewed");
        }
        if (decision != BrandApplicationStatus.NEEDS_INFORMATION
                && decision != BrandApplicationStatus.APPROVED
                && decision != BrandApplicationStatus.REJECTED) {
            throw new IllegalArgumentException("review decision is invalid");
        }
        if ((decision == BrandApplicationStatus.APPROVED) != (approvedBrand != null)) {
            throw new IllegalArgumentException("approved brand is required only for approval");
        }
        status = decision;
        reviewNote = blankToNull(note);
        reviewedAt = LocalDateTime.now(Clock.systemUTC());
        brand = approvedBrand;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public Long getId() { return id; }
    public String getBrandName() { return brandName; }
    public String getContactName() { return contactName; }
    public String getContactEmail() { return contactEmail; }
    public String getWebsite() { return website; }
    public String getMessage() { return message; }
    public BrandApplicationStatus getStatus() { return status; }
    public String getReviewNote() { return reviewNote; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public Brand getBrand() { return brand; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
