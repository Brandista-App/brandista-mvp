package com.brandista.brandista.privilege;

import com.brandista.brandista.user.User;
import jakarta.persistence.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "user_platform_grants", uniqueConstraints =
        @UniqueConstraint(name = "uk_platform_grant_user_type", columnNames = {"user_id", "grant_type"}))
public class PlatformGrant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", nullable = false, length = 32)
    private PlatformGrantType grantType;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PlatformGrant() { }

    /** Internal persistence model; no HTTP endpoint can create or modify grants. */
    public PlatformGrant(User user, PlatformGrantType grantType) {
        this.user = Objects.requireNonNull(user);
        this.grantType = Objects.requireNonNull(grantType);
        this.createdAt = LocalDateTime.now(Clock.systemUTC());
    }

    public Long getId() { return id; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void revoke() {
        if (revokedAt == null) revokedAt = LocalDateTime.now(Clock.systemUTC());
    }
}
