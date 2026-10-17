package com.brandista.brandista.privilege;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformGrantRepository extends JpaRepository<PlatformGrant, Long> {
    boolean existsByUserIdAndGrantTypeAndRevokedAtIsNull(Long userId, PlatformGrantType grantType);
}
