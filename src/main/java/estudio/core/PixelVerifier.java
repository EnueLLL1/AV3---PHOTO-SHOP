package estudio.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class PixelVerifier {
    public record Result(
            boolean exactMatch,
            long totalPixels,
            long diffPixels,
            int firstX,
            int firstY,
            int expectedArgb,
            int actualArgb) {
    }

    private PixelVerifier() {
    }

    public static Result verify(ImageBuffer expected, ImageBuffer actual) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        if (expected.width != actual.width || expected.height != actual.height) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: expected=" + expected.width + "x" + expected.height
                            + ", actual=" + actual.width + "x" + actual.height);
        }

        int w = expected.width;
        int h = expected.height;
        int[] ep = expected.pixels;
        int[] ap = actual.pixels;
        long totalPixels = (long) w * h;
        long diffPixels = 0L;
        int firstX = -1;
        int firstY = -1;
        int firstExpected = 0;
        int firstActual = 0;

        for (int y = 0; y < h; y++) {
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                int idx = rowOffset + x;
                int e = ep[idx];
                int a = ap[idx];
                if (e != a) {
                    if (diffPixels == 0L) {
                        firstX = x;
                        firstY = y;
                        firstExpected = e;
                        firstActual = a;
                    }
                    diffPixels++;
                }
            }
        }

        return new Result(
                diffPixels == 0L,
                totalPixels,
                diffPixels,
                firstX,
                firstY,
                firstExpected,
                firstActual);
    }

    public static Result verifyDirectories(List<Path> expectedFiles, List<Path> actualFiles) throws IOException {
        Objects.requireNonNull(expectedFiles, "expectedFiles must not be null");
        Objects.requireNonNull(actualFiles, "actualFiles must not be null");
        if (expectedFiles.size() != actualFiles.size()) {
            throw new IllegalArgumentException(
                    "File count mismatch: expected=" + expectedFiles.size() + ", actual=" + actualFiles.size());
        }

        long totalPixels = 0L;
        long totalDiffs = 0L;
        int firstX = -1;
        int firstY = -1;
        int firstExpected = 0;
        int firstActual = 0;

        for (int i = 0; i < expectedFiles.size(); i++) {
            ImageBuffer expected = ImageBuffer.read(expectedFiles.get(i));
            ImageBuffer actual = ImageBuffer.read(actualFiles.get(i));
            Result r = verify(expected, actual);
            totalPixels += r.totalPixels();
            if (!r.exactMatch()) {
                if (totalDiffs == 0L) {
                    firstX = r.firstX();
                    firstY = r.firstY();
                    firstExpected = r.expectedArgb();
                    firstActual = r.actualArgb();
                }
                totalDiffs += r.diffPixels();
            }
        }

        return new Result(
                totalDiffs == 0L,
                totalPixels,
                totalDiffs,
                firstX,
                firstY,
                firstExpected,
                firstActual);
    }
}
