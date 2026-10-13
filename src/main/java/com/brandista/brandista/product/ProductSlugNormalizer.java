package com.brandista.brandista.product;

import java.util.Locale;

public final class ProductSlugNormalizer {

    private ProductSlugNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) return null;
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
