package com.brandista.brandista.brandapplication.api;

import com.brandista.brandista.brandapplication.service.BrandApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/brand-applications")
public class PublicBrandApplicationController {
    private final BrandApplicationService service;
    public PublicBrandApplicationController(BrandApplicationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BrandApplicationSubmittedResponse submit(@Valid @RequestBody BrandApplicationSubmissionRequest request) {
        BrandApplicationResponse submitted = service.submit(request);
        return new BrandApplicationSubmittedResponse(submitted.id(), submitted.status(), submitted.createdAt());
    }
}
