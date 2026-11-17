package estudio.core;

import estudio.model.FilterType;

import java.util.Objects;

public final class ConvolutionOps {
    private static final int[] GAUSS_5_WEIGHTS = {1, 4, 6, 4, 1};

    private ConvolutionOps() {
    }

    public static int isqrt(int n) {
        if (n <= 0) {
            return 0;
        }
        int lo = 1;
        int hi = Math.min(n, 46340);
        int ans = 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int sq = mid * mid;
            if (sq == n) {
                return mid;
            }
            if (sq < n) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    public static int clamp255(int v) {
        if (v < 0) {
            return 0;
        }
        return v > 255 ? 255 : v;
    }

    public static int clampCoord(int c, int maxInclusive) {
        if (c < 0) {
            return 0;
        }
        return c > maxInclusive ? maxInclusive : c;
    }

    public static void applyFull(ImageBuffer src, ImageBuffer dst, FilterType filter) {
        Objects.requireNonNull(src, "src must not be null");
        applyRegion(src, dst, filter, 0, src.width, 0, src.height);
    }

    public static void applyRows(ImageBuffer src, ImageBuffer dst, FilterType filter, int yStart, int yEnd) {
        Objects.requireNonNull(src, "src must not be null");
        applyRegion(src, dst, filter, 0, src.width, yStart, yEnd);
    }

    public static void applyRegion(
            ImageBuffer src,
            ImageBuffer dst,
            FilterType filter,
            int xStart,
            int xEnd,
            int yStart,
            int yEnd) {
        Objects.requireNonNull(src, "src must not be null");
        Objects.requireNonNull(dst, "dst must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        if (src.width != dst.width || src.height != dst.height) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: src=" + src.width + "x" + src.height
                            + ", dst=" + dst.width + "x" + dst.height);
        }
        if (xStart < 0 || xStart > xEnd || xEnd > src.width) {
            throw new IllegalArgumentException("Invalid x range [" + xStart + ", " + xEnd + ") for width " + src.width);
        }
        if (yStart < 0 || yStart > yEnd || yEnd > src.height) {
            throw new IllegalArgumentException("Invalid y range [" + yStart + ", " + yEnd + ") for height " + src.height);
        }

        switch (filter) {
            case BLUR -> applyBlur(src, dst, xStart, xEnd, yStart, yEnd);
            case SHARPEN -> applySharpen(src, dst, xStart, xEnd, yStart, yEnd);
            case SOBEL -> applySobel(src, dst, xStart, xEnd, yStart, yEnd);
        }
    }

    private static void applyBlur(
            ImageBuffer src, ImageBuffer dst, int xStart, int xEnd, int yStart, int yEnd) {
        final int w = src.width;
        final int maxX = w - 1;
        final int maxY = src.height - 1;
        final int[] sp = src.pixels;
        final int[] dp = dst.pixels;

        for (int y = yStart; y < yEnd; y++) {
            int rowOffset = y * w;
            for (int x = xStart; x < xEnd; x++) {
                int rSum = 0;
                int gSum = 0;
                int bSum = 0;

                for (int dy = -2; dy <= 2; dy++) {
                    int sy = clampCoord(y + dy, maxY);
                    int sRow = sy * w;
                    int wy = GAUSS_5_WEIGHTS[dy + 2];
                    for (int dx = -2; dx <= 2; dx++) {
                        int sx = clampCoord(x + dx, maxX);
                        int weight = wy * GAUSS_5_WEIGHTS[dx + 2];
                        int p = sp[sRow + sx];
                        rSum += ((p >>> 16) & 0xFF) * weight;
                        gSum += ((p >>> 8) & 0xFF) * weight;
                        bSum += (p & 0xFF) * weight;
                    }
                }

                int alpha = sp[rowOffset + x] & 0xFF000000;
                int r = (rSum + 128) >> 8;
                int g = (gSum + 128) >> 8;
                int b = (bSum + 128) >> 8;
                dp[rowOffset + x] = alpha | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void applySharpen(
            ImageBuffer src, ImageBuffer dst, int xStart, int xEnd, int yStart, int yEnd) {
        final int w = src.width;
        final int maxX = w - 1;
        final int maxY = src.height - 1;
        final int[] sp = src.pixels;
        final int[] dp = dst.pixels;

        for (int y = yStart; y < yEnd; y++) {
            int rowOffset = y * w;
            int topRow = clampCoord(y - 1, maxY) * w;
            int botRow = clampCoord(y + 1, maxY) * w;
            for (int x = xStart; x < xEnd; x++) {
                int xm1 = clampCoord(x - 1, maxX);
                int xp1 = clampCoord(x + 1, maxX);

                int c = sp[rowOffset + x];
                int top = sp[topRow + x];
                int bot = sp[botRow + x];
                int left = sp[rowOffset + xm1];
                int right = sp[rowOffset + xp1];

                int r = clamp255(
                        5 * ((c >>> 16) & 0xFF)
                                - ((top >>> 16) & 0xFF)
                                - ((bot >>> 16) & 0xFF)
                                - ((left >>> 16) & 0xFF)
                                - ((right >>> 16) & 0xFF));
                int g = clamp255(
                        5 * ((c >>> 8) & 0xFF)
                                - ((top >>> 8) & 0xFF)
                                - ((bot >>> 8) & 0xFF)
                                - ((left >>> 8) & 0xFF)
                                - ((right >>> 8) & 0xFF));
                int b = clamp255(
                        5 * (c & 0xFF)
                                - (top & 0xFF)
                                - (bot & 0xFF)
                                - (left & 0xFF)
                                - (right & 0xFF));

                dp[rowOffset + x] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static void applySobel(
            ImageBuffer src, ImageBuffer dst, int xStart, int xEnd, int yStart, int yEnd) {
        final int w = src.width;
        final int maxX = w - 1;
        final int maxY = src.height - 1;
        final int[] sp = src.pixels;
        final int[] dp = dst.pixels;

        for (int y = yStart; y < yEnd; y++) {
            int rowOffset = y * w;
            int ym1Row = clampCoord(y - 1, maxY) * w;
            int yp1Row = clampCoord(y + 1, maxY) * w;
            for (int x = xStart; x < xEnd; x++) {
                int xm1 = clampCoord(x - 1, maxX);
                int xp1 = clampCoord(x + 1, maxX);

                int l00 = luminance(sp[ym1Row + xm1]);
                int l01 = luminance(sp[ym1Row + x]);
                int l02 = luminance(sp[ym1Row + xp1]);

                int l10 = luminance(sp[rowOffset + xm1]);
                int l12 = luminance(sp[rowOffset + xp1]);

                int l20 = luminance(sp[yp1Row + xm1]);
                int l21 = luminance(sp[yp1Row + x]);
                int l22 = luminance(sp[yp1Row + xp1]);

                int gx = -l00 + l02 - 2 * l10 + 2 * l12 - l20 + l22;
                int gy = -l00 - 2 * l01 - l02 + l20 + 2 * l21 + l22;

                int mag = clamp255(isqrt(gx * gx + gy * gy));
                int alpha = sp[rowOffset + x] & 0xFF000000;
                dp[rowOffset + x] = alpha | (mag << 16) | (mag << 8) | mag;
            }
        }
    }

    private static int luminance(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        return (77 * r + 150 * g + 29 * b) >> 8;
    }
}
