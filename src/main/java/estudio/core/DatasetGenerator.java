package estudio.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DatasetGenerator {
    public static final int DEFAULT_COUNT = 20;
    public static final int DEFAULT_WIDTH = 4000;
    public static final int DEFAULT_HEIGHT = 3000;

    private DatasetGenerator() {
    }

    public static ImageBuffer generateImage(int index, int width, int height) {
        ImageBuffer buffer = new ImageBuffer(width, height);
        int[] pixels = buffer.pixels;

        int tileSize = Math.max(16, Math.min(width, height) / 32);
        int shiftX = (index * 37) % width;
        int shiftY = (index * 53) % height;

        int cx1 = (width * (2 + (index % 5))) / 8;
        int cy1 = (height * (2 + (index % 4))) / 7;
        int r1 = Math.max(20, Math.min(width, height) / 6);
        long r1Sq = (long) r1 * r1;

        int cx2 = width - 1 - cx1 / 2;
        int cy2 = height - 1 - cy1 / 2;
        int r2 = Math.max(15, r1 / 2);
        long r2Sq = (long) r2 * r2;

        int rectX0 = width / 10 + (index * 19) % Math.max(1, width / 5);
        int rectY0 = height / 10 + (index * 23) % Math.max(1, height / 5);
        int rectX1 = Math.min(width, rectX0 + Math.max(32, width / 4));
        int rectY1 = Math.min(height, rectY0 + Math.max(32, height / 4));

        int wMinus1 = Math.max(1, width - 1);
        int hMinus1 = Math.max(1, height - 1);

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            int gBase = (y * 255) / hMinus1;
            int checkerY = ((y + shiftY) / tileSize) & 1;
            boolean inRectY = y >= rectY0 && y < rectY1;
            long dy1 = y - cy1;
            long dy1Sq = dy1 * dy1;
            long dy2 = y - cy2;
            long dy2Sq = dy2 * dy2;

            for (int x = 0; x < width; x++) {
                int r = (x * 255) / wMinus1;
                int g = gBase;
                int b = ((x + y + index * 29) * 255) / (wMinus1 + hMinus1);

                // Padrao xadrez no quadrante inferior esquerdo para testar altas frequencias (Blur/Sharpen)
                if (x < width / 2 && y >= height / 2) {
                    int checker = (((x + shiftX) / tileSize) & 1) ^ checkerY;
                    if (checker == 0) {
                        r = 240;
                        g = 240;
                        b = 240;
                    } else {
                        r = 20;
                        g = 25;
                        b = 35;
                    }
                }

                // Retangulo com bordas nitidas (Sobel)
                if (inRectY && x >= rectX0 && x < rectX1) {
                    boolean border = x < rectX0 + 4 || x >= rectX1 - 4 || y < rectY0 + 4 || y >= rectY1 - 4;
                    if (border) {
                        r = 255;
                        g = 255;
                        b = 255;
                    } else {
                        r = (r + 180) & 0xFF;
                        g = (g + 40) & 0xFF;
                        b = (255 - b);
                    }
                }

                // Circulo principal
                long dx1 = x - cx1;
                long d1 = dx1 * dx1 + dy1Sq;
                if (d1 <= r1Sq) {
                    r = 220;
                    g = 60 + ((x * 80) / wMinus1);
                    b = 40;
                }

                // Circulo secundario
                long dx2 = x - cx2;
                long d2 = dx2 * dx2 + dy2Sq;
                if (d2 <= r2Sq) {
                    r = 30;
                    g = 210;
                    b = 230;
                }

                // Listras diagonais nitidas
                if (((x + y + index * 17) & 63) < 3) {
                    r = 15;
                    g = 15;
                    b = 15;
                }

                pixels[rowOffset + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        return buffer;
    }

    public static List<Path> generateDataset(Path outDir, int count, int width, int height) throws IOException {
        Objects.requireNonNull(outDir, "outDir must not be null");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0: " + count);
        }
        Files.createDirectories(outDir);
        List<Path> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String fileName = String.format("img_%03d.png", i + 1);
            Path filePath = outDir.resolve(fileName);
            ImageBuffer img = generateImage(i, width, height);
            img.writePng(filePath);
            files.add(filePath);
        }
        return files;
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of("data", "input");
        int count = DEFAULT_COUNT;
        int width = DEFAULT_WIDTH;
        int height = DEFAULT_HEIGHT;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--out" -> outDir = Path.of(requireValue(args, ++i, "--out"));
                case "--count" -> count = Integer.parseInt(requireValue(args, ++i, "--count"));
                case "--width" -> width = Integer.parseInt(requireValue(args, ++i, "--width"));
                case "--height" -> height = Integer.parseInt(requireValue(args, ++i, "--height"));
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        long t0 = System.nanoTime();
        List<Path> generated = generateDataset(outDir, count, width, height);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("Generated %d PNG images (%dx%d) in %s (%d ms)%n",
                generated.size(), width, height, outDir.toAbsolutePath(), elapsedMs);
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[idx];
    }
}
