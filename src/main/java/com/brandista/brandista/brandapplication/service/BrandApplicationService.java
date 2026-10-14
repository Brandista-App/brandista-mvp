package com.brandista.brandista.brandapplication.service;

import com.brandista.brandista.brand.Brand;
import com.brandista.brandista.brand.BrandRepository;
import com.brandista.brandista.brand.BrandSlugNormalizer;
import com.brandista.brandista.brandapplication.BrandApplication;
import com.brandista.brandista.brandapplication.BrandApplicationRepository;
import com.brandista.brandista.brandapplication.BrandApplicationStatus;
import com.brandista.brandista.brandapplication.api.BrandApplicationResponse;
import com.brandista.brandista.brandapplication.api.BrandApplicationReviewRequest;
import com.brandista.brandista.brandapplication.api.BrandApplicationSubmissionRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BrandApplicationService {
    private static final List<BrandApplicationStatus> OPEN_STATUSES = List.of(
            BrandApplicationStatus.SUBMITTED, BrandApplicationStatus.NEEDS_INFORMATION);
    private final BrandApplicationRepository applicationRepository;
    private final BrandRepository brandRepository;

    public BrandApplicationService(BrandApplicationRepository applicationRepository, BrandRepository brandRepository) {
        this.applicationRepository = applicationRepository;
        this.brandRepository = brandRepository;
    }

    @Transactional
    public BrandApplicationResponse submit(BrandApplicationSubmissionRequest request) {
        String email = request.contactEmail().trim().toLowerCase(java.util.Locale.ROOT);
        if (applicationRepository.existsByContactEmailAndStatusIn(email, OPEN_STATUSES)) {
            throw new DuplicateOpenBrandApplicationException();
        }
        return toResponse(applicationRepository.saveAndFlush(new BrandApplication(
                request.brandName(), request.contactName(), email, request.website(), request.message())));
    }

    @Transactional(readOnly = true)
    public List<BrandApplicationResponse> list() {
        return applicationRepository.findAllByOrderByCreatedAtDesc().stream().map(BrandApplicationService::toResponse).toList();
    }

    @Transactional
    public BrandApplicationResponse review(Long id, BrandApplicationReviewRequest request) {
        BrandApplication application = applicationRepository.findById(id)
                .orElseThrow(BrandApplicationNotFoundException::new);
        if (!OPEN_STATUSES.contains(application.getStatus())) throw new BrandApplicationReviewConflictException();

        Brand approvedBrand = null;
        if (request.decision() == BrandApplicationStatus.APPROVED) {
            String slug = application.getBrandName().trim().toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            slug = BrandSlugNormalizer.normalize(slug);
            if (slug == null || slug.isBlank() || brandRepository.existsBySlug(slug)) {
                throw new BrandApplicationApprovalException(
                        "Brand name cannot be converted to a unique storefront slug; resolve the duplicate before approval.");
            }
            approvedBrand = new Brand(application.getBrandName(), slug, null, null);
            approvedBrand.deactivate();
            approvedBrand = brandRepository.saveAndFlush(approvedBrand);
        }
        try {
            application.review(request.decision(), request.reviewNote(), approvedBrand);
        } catch (IllegalStateException exception) {
            throw new BrandApplicationReviewConflictException();
        }
        return toResponse(applicationRepository.saveAndFlush(application));
    }

    private static BrandApplicationResponse toResponse(BrandApplication application) {
        Brand brand = application.getBrand();
        return new BrandApplicationResponse(application.getId(), application.getBrandName(), application.getContactName(),
                application.getContactEmail(), application.getWebsite(), application.getMessage(), application.getStatus(),
                application.getReviewNote(), application.getReviewedAt(), brand == null ? null : brand.getId(),
                brand == null ? null : brand.getStatus().name(), application.getCreatedAt(), application.getUpdatedAt());
    }
}
