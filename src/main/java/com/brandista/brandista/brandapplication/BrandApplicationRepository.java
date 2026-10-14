package com.brandista.brandista.brandapplication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BrandApplicationRepository extends JpaRepository<BrandApplication, Long> {
    boolean existsByContactEmailAndStatusIn(String contactEmail, Collection<BrandApplicationStatus> statuses);
    List<BrandApplication> findAllByOrderByCreatedAtDesc();
}
