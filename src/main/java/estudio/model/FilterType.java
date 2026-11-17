package estudio.model;

import java.util.Locale;

public enum FilterType {
    BLUR("Gaussian Blur 5x5", 2),
    SHARPEN("Sharpen 3x3", 1),
    SOBEL("Sobel 3x3", 1);

    private final String label;
    private final int radius;

    FilterType(String label, int radius) {
        this.label = label;
        this.radius = radius;
    }

    public String label() {
        return label;
    }

    public int radius() {
        return radius;
    }

    public static FilterType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FilterType value cannot be null or blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (FilterType type : values()) {
            if (type.name().equals(normalized) || type.label.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown FilterType: " + value);
    }
}
