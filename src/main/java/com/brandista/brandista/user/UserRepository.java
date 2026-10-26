package com.brandista.brandista.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface UserRepository extends com.brandista.brandista.privilege.SecurityEntityRepository<User> {

    Optional<User> findByEmail(String email);

    // The unique (user_id, grant_type) constraint makes this at most one row.
    // A single statement observes account and grant state in the same DB snapshot.
    @Query("select new com.brandista.brandista.user.CurrentAuthorization(u.role,u.status," +
            "case when g.id is null then false else true end) from User u " +
            "left join PlatformGrant g on g.user.id=u.id " +
            "and g.grantType=com.brandista.brandista.privilege.PlatformGrantType.SUPER_ADMIN " +
            "and g.revokedAt is null where u.id=:id")
    Optional<CurrentAuthorization> findCurrentAuthorizationById(@Param("id") Long id);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
