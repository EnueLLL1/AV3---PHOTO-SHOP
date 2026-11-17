package estudio.model;

import java.util.Locale;

public enum ExecutionMode {
    SEQUENTIAL,
    THREADS_ROWS,
    THREADS_BLOCKS,
    MPJ,
    GPU,
    BENCHMARK_ALL;

    public static ExecutionMode fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ExecutionMode value cannot be null or blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ExecutionMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown ExecutionMode: " + value);
    }
}
