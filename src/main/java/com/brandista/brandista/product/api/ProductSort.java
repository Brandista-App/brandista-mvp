package com.brandista.brandista.product.api;

public enum ProductSort {
    DEFAULT,
    PRICE_ASC,
    PRICE_DESC;

    public static ProductSort fromQuery(String value) {
        return switch (value) {
            case "price_asc" -> PRICE_ASC;
            case "price_desc" -> PRICE_DESC;
            default -> DEFAULT;
        };
    }
}
