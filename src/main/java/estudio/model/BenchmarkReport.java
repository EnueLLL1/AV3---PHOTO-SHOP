package estudio.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class BenchmarkReport implements Serializable {
    private static final long serialVersionUID = 1L;

    public static class Entry implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String versionName;
        private final int workers;
        private final long computeMs;
        private final long totalMs;
        private final double speedup;
        private final double efficiencyPct;
        private final long diffPixels;
        private final boolean verified;
        private final String notes;

        public Entry(
                String versionName,
                int workers,
                long computeMs,
                long totalMs,
                double speedup,
                double efficiencyPct,
                long diffPixels,
                boolean verified,
                String notes) {
            this.versionName = Objects.requireNonNull(versionName, "versionName must not be null");
            this.workers = Math.max(1, workers);
            this.computeMs = Math.max(0L, computeMs);
            this.totalMs = Math.max(0L, totalMs);
            this.speedup = speedup;
            this.efficiencyPct = efficiencyPct;
            this.diffPixels = Math.max(0L, diffPixels);
            this.verified = verified;
            this.notes = notes == null ? "" : notes;
        }

        public String getVersionName() {
            return versionName;
        }

        public int getWorkers() {
            return workers;
        }

        public long getComputeMs() {
            return computeMs;
        }

        public long getTotalMs() {
            return totalMs;
        }

        public double getSpeedup() {
            return speedup;
        }

        public double getEfficiencyPct() {
            return efficiencyPct;
        }

        public long getDiffPixels() {
            return diffPixels;
        }

        public boolean isVerified() {
            return verified;
        }

        public String getNotes() {
            return notes;
        }
    }

    private final FilterType filter;
    private final int imageCount;
    private final int width;
    private final int height;
    private final ArrayList<Entry> entries;

    public BenchmarkReport(FilterType filter, int imageCount, int width, int height, List<Entry> entries) {
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
        this.imageCount = Math.max(0, imageCount);
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.entries = new ArrayList<>(Objects.requireNonNull(entries, "entries must not be null"));
    }

    public FilterType getFilter() {
        return filter;
    }

    public int getImageCount() {
        return imageCount;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public String formatTable() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Benchmark Report - Filtro: %s (%s) | Imagens: %d (%dx%d)%n",
                filter.name(), filter.label(), imageCount, width, height));
        String separator = "+----------------------+---------+------------+-----------+---------+---------------+-------------+--------+------------------------------+%n";
        sb.append(String.format(separator));
        sb.append(String.format(
                "| %-20s | %7s | %10s | %9s | %7s | %13s | %11s | %-6s | %-28s |%n",
                "Versao", "Workers", "Compute ms", "Tempo ms", "Speedup", "Eficiencia %", "Diff Pixels", "Status", "Notas"));
        sb.append(String.format(separator));
        for (Entry e : entries) {
            String status = e.verified ? "OK" : "FAIL";
            sb.append(String.format(Locale.ROOT,
                    "| %-20s | %7d | %10d | %9d | %6.2fx | %12.1f%% | %11d | %-6s | %-28s |%n",
                    truncate(e.versionName, 20),
                    e.workers,
                    e.computeMs,
                    e.totalMs,
                    e.speedup,
                    e.efficiencyPct,
                    e.diffPixels,
                    status,
                    truncate(e.notes, 28)));
        }
        sb.append(String.format(separator));
        return sb.toString();
    }

    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("filter,imageCount,width,height,versionName,workers,computeMs,totalMs,speedup,efficiencyPct,diffPixels,verified,notes\n");
        for (Entry e : entries) {
            sb.append(filter.name()).append(',')
                    .append(imageCount).append(',')
                    .append(width).append(',')
                    .append(height).append(',')
                    .append(csvEscape(e.versionName)).append(',')
                    .append(e.workers).append(',')
                    .append(e.computeMs).append(',')
                    .append(e.totalMs).append(',')
                    .append(String.format(Locale.ROOT, "%.4f", e.speedup)).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", e.efficiencyPct)).append(',')
                    .append(e.diffPixels).append(',')
                    .append(e.verified).append(',')
                    .append(csvEscape(e.notes)).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return maxLen <= 3 ? s.substring(0, maxLen) : s.substring(0, maxLen - 3) + "...";
    }

    private static String csvEscape(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
