package com.brandista.brandista.brandapplication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BrandApplicationRepository extends JpaRepository<BrandApplication, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from BrandApplication a where a.id=:id")
    java.util.Optional<BrandApplication> findForReview(@org.springframework.data.repository.query.Param("id") Long id);
    boolean existsByContactEmailAndStatusIn(String contactEmail, Collection<BrandApplicationStatus> statuses);
    List<BrandApplication> findAllByOrderByCreatedAtDesc();
}
