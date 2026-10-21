package com.brandista.brandista.merchant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.brandista.brandista.merchant.MerchantOrderModels.*;

@RestController
@Validated
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/merchant/brands/{brandId}/orders")
public class MerchantOrderController {
    private final MerchantOrderService service;
    public MerchantOrderController(MerchantOrderService service) { this.service=service; }
    @GetMapping
    public Page list(@PathVariable @Positive long brandId,@RequestParam(defaultValue="0") @Min(0) int page,
                     @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
                     @RequestParam(required=false) Status status) { return service.list(brandId,page,size,status); }
    @GetMapping("/{orderId}")
    public Detail get(@PathVariable @Positive long brandId,@PathVariable @Positive long orderId) {
        return service.get(brandId,orderId);
    }
    @PostMapping("/{orderId}/fulfillment")
    public Detail transition(@PathVariable @Positive long brandId,@PathVariable @Positive long orderId,
                             @Valid @RequestBody Transition request) { return service.transition(brandId,orderId,request); }
}
