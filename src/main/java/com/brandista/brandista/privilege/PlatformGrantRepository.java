package com.brandista.brandista.privilege;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformGrantRepository extends SecurityEntityRepository<PlatformGrant> {
    boolean existsByUserIdAndGrantTypeAndRevokedAtIsNull(Long userId, PlatformGrantType grantType);
}
