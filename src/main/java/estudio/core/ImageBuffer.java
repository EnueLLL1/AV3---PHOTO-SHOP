package estudio.core;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class ImageBuffer {
    public final int width;
    public final int height;
    public final int[] pixels; // 0xAARRGGBB

    public ImageBuffer(int width, int height) {
        this(width, height, new int[validateSize(width, height)]);
    }

    public ImageBuffer(int width, int height, int[] pixels) {
        int expectedLen = validateSize(width, height);
        Objects.requireNonNull(pixels, "pixels must not be null");
        if (pixels.length != expectedLen) {
            throw new IllegalArgumentException(
                    "pixels.length (" + pixels.length + ") != width * height (" + expectedLen + ")");
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    private static int validateSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions: " + width + "x" + height);
        }
        long total = (long) width * height;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Image too large: " + width + "x" + height);
        }
        return (int) total;
    }

    public static ImageBuffer read(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) {
            throw new IOException("Unsupported or unreadable image: " + path);
        }
        return fromBufferedImage(img);
    }

    public static ImageBuffer fromBytes(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes must not be null");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            throw new IOException("Unsupported or unreadable image bytes (" + bytes.length + " bytes)");
        }
        return fromBufferedImage(img);
    }

    private static ImageBuffer fromBufferedImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        return new ImageBuffer(w, h, argb);
    }

    public void writePng(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedImage img = toBufferedImage();
        if (!ImageIO.write(img, "png", path.toFile())) {
            throw new IOException("No PNG writer available for " + path);
        }
    }

    public byte[] toPngBytes() throws IOException {
        BufferedImage img = toBufferedImage();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(width * height);
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("No PNG writer available");
        }
        return baos.toByteArray();
    }

    private BufferedImage toBufferedImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    public ImageBuffer cloneEmpty() {
        return new ImageBuffer(width, height);
    }

    public ImageBuffer sliceRows(int startY, int endY) {
        if (startY < 0 || startY >= endY || endY > height) {
            throw new IllegalArgumentException(
                    "Invalid row slice [" + startY + ", " + endY + ") for height " + height);
        }
        int rowCount = endY - startY;
        ImageBuffer slice = new ImageBuffer(width, rowCount);
        System.arraycopy(pixels, startY * width, slice.pixels, 0, rowCount * width);
        return slice;
    }

    public void copyRowsTo(int dstStartY, ImageBuffer srcSlice, int srcStartY, int rowCount) {
        Objects.requireNonNull(srcSlice, "srcSlice must not be null");
        if (srcSlice.width != this.width) {
            throw new IllegalArgumentException(
                    "Width mismatch: expected " + this.width + ", got " + srcSlice.width);
        }
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be >= 0: " + rowCount);
        }
        if (srcStartY < 0 || srcStartY + rowCount > srcSlice.height) {
            throw new IllegalArgumentException(
                    "Source rows out of bounds: srcStartY=" + srcStartY + ", rowCount=" + rowCount + ", srcHeight=" + srcSlice.height);
        }
        if (dstStartY < 0 || dstStartY + rowCount > this.height) {
            throw new IllegalArgumentException(
                    "Destination rows out of bounds: dstStartY=" + dstStartY + ", rowCount=" + rowCount + ", dstHeight=" + this.height);
        }
        if (rowCount == 0) {
            return;
        }
        System.arraycopy(srcSlice.pixels, srcStartY * width, this.pixels, dstStartY * width, rowCount * width);
    }
}
