package com.brandista.brandista.brand.service;

import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.brand.BrandRepository;
import com.brandista.brandista.brand.BrandSlugNormalizer;
import com.brandista.brandista.brand.BrandStatus;
import com.brandista.brandista.brand.api.BrandResponse;
import com.brandista.brandista.brand.api.BrandCreateRequest;
import com.brandista.brandista.brand.api.BrandUpdateRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BrandService {

    private final BrandRepository brandRepository;
    private final com.brandista.brandista.onboarding.OnboardingPolicy onboarding;

    public BrandService(BrandRepository brandRepository,com.brandista.brandista.onboarding.OnboardingPolicy onboarding) {
        this.brandRepository = brandRepository;
        this.onboarding=onboarding;
    }

    @Transactional
    public BrandResponse create(BrandCreateRequest request) {
        String slug = BrandSlugNormalizer.normalize(request.slug());
        if (brandRepository.existsBySlug(slug)) {
            throw new DuplicateBrandSlugException();
        }
        Brand brand = new Brand(request.name(), slug, request.description(), request.logoReference());
        try {
            return toResponse(brandRepository.saveAndFlush(brand));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateBrandSlugException();
        }
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> listActive() {
        return brandRepository.findAllByStatusOrderByNameAsc(BrandStatus.ACTIVE)
                .stream()
                .map(BrandService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> listAll() {
        return brandRepository.findAllByOrderByNameAsc()
                .stream()
                .map(BrandService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BrandResponse getById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public BrandResponse update(Long id, BrandUpdateRequest request) {
        if(request.status()==BrandStatus.ACTIVE) onboarding.guardGlobalActivation(id);
        Brand brand = findById(id);
        String slug = BrandSlugNormalizer.normalize(request.slug());
        if (brandRepository.existsBySlugAndIdNot(slug, id)) {
            throw new DuplicateBrandSlugException();
        }
        brand.updateDetails(request.name(), slug, request.description(), request.logoReference(), request.status());
        try {
            return toResponse(brandRepository.saveAndFlush(brand));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateBrandSlugException();
        }
    }

    @Transactional
    public BrandResponse deactivate(Long id) {
        Brand brand = findById(id);
        brand.deactivate();
        return toResponse(brandRepository.saveAndFlush(brand));
    }

    private Brand findById(Long id) {
        return brandRepository.findById(id).orElseThrow(BrandNotFoundException::new);
    }

    private static BrandResponse toResponse(Brand brand) {
        return new BrandResponse(
                brand.getId(),
                brand.getName(),
                brand.getSlug(),
                brand.getDescription(),
                brand.getLogoReference(),
                brand.getStatus(),
                brand.getCreatedAt(),
                brand.getUpdatedAt());
    }
}
