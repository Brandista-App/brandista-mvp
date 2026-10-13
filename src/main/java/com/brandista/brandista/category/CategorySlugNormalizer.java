package com.brandista.brandista.category;

import java.util.Locale;

public final class CategorySlugNormalizer {

    private CategorySlugNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
