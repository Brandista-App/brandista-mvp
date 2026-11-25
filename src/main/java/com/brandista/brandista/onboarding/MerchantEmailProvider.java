package com.brandista.brandista.onboarding;

public interface MerchantEmailProvider {
    String send(String key,String email,String brand,String token,String expires);
    String status(String providerId);
}
