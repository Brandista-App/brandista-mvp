package com.brandista.brandista.brand;

import java.util.Locale;

public final class BrandSlugNormalizer {

    private BrandSlugNormalizer() {
    }

    public static String normalize(String slug) {
        if (slug == null) {
            return null;
        }
        return slug.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
