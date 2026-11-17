package estudio;

import estudio.core.ConvolutionOps;
import estudio.core.ImageBuffer;
import estudio.core.PixelVerifier;
import estudio.model.FilterType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvolutionOpsTest {

    private static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    @Test
    void testScalarHelpers() {
        assertEquals(0, ConvolutionOps.isqrt(-10));
        assertEquals(0, ConvolutionOps.isqrt(0));
        assertEquals(1, ConvolutionOps.isqrt(1));
        assertEquals(1, ConvolutionOps.isqrt(2));
        assertEquals(2, ConvolutionOps.isqrt(8));
        assertEquals(3, ConvolutionOps.isqrt(9));
        assertEquals(15, ConvolutionOps.isqrt(255));
        assertEquals(256, ConvolutionOps.isqrt(65536));
        assertEquals(46340, ConvolutionOps.isqrt(Integer.MAX_VALUE));

        assertEquals(0, ConvolutionOps.clamp255(-100));
        assertEquals(0, ConvolutionOps.clamp255(0));
        assertEquals(128, ConvolutionOps.clamp255(128));
        assertEquals(255, ConvolutionOps.clamp255(255));
        assertEquals(255, ConvolutionOps.clamp255(500));

        assertEquals(0, ConvolutionOps.clampCoord(-3, 4));
        assertEquals(0, ConvolutionOps.clampCoord(0, 4));
        assertEquals(2, ConvolutionOps.clampCoord(2, 4));
        assertEquals(4, ConvolutionOps.clampCoord(4, 4));
        assertEquals(4, ConvolutionOps.clampCoord(9, 4));
    }

    @Test
    void testBlur5x5ExactWithClampToEdgeAndAlpha() {
        ImageBuffer src = new ImageBuffer(5, 5);
        int bg = argb(0x11, 0, 16, 32);
        for (int i = 0; i < src.pixels.length; i++) {
            src.pixels[i] = bg;
        }
        // Center pixel with distinct RGB and Alpha
        src.pixels[2 * 5 + 2] = argb(0x7A, 255, 0, 0);

        ImageBuffer dst = src.cloneEmpty();
        ConvolutionOps.applyFull(src, dst, FilterType.BLUR);

        // Center (2,2): weight(2,2)=36, other 24 pixels weight=220
        // R = (255*36 + 128) >> 8 = 36; G = (16*220 + 128) >> 8 = 14; B = (32*220 + 128) >> 8 = 28
        assertEquals(argb(0x7A, 36, 14, 28), dst.pixels[2 * 5 + 2]);

        // Corner (0,0) with Clamp-to-Edge: weight(2,2)=1*1=1, remaining 255 weight on bg
        // R = (255 + 128) >> 8 = 1; G = (16*255 + 128) >> 8 = 16; B = (32*255 + 128) >> 8 = 32
        assertEquals(argb(0x11, 1, 16, 32), dst.pixels[0]);

        // 3x3 boundary clamp check where (0,0) carries 11*11 = 121 weight units onto itself
        ImageBuffer small = new ImageBuffer(3, 3);
        for (int i = 0; i < small.pixels.length; i++) {
            small.pixels[i] = argb(0xAB, 0, 0, 0);
        }
        small.pixels[0] = argb(0x23, 200, 100, 50);

        ImageBuffer smallDst = small.cloneEmpty();
        ConvolutionOps.applyFull(small, smallDst, FilterType.BLUR);
        // R = (200*121 + 128) >> 8 = 95; G = (100*121 + 128) >> 8 = 47; B = (50*121 + 128) >> 8 = 24
        assertEquals(argb(0x23, 95, 47, 24), smallDst.pixels[0]);
    }

    @Test
    void testSharpen3x3ExactWithClampAndAlpha() {
        int[] pixels = {
                argb(0x12, 10, 20, 30), argb(0x34, 40, 50, 60), argb(0x56, 0, 0, 0),
                argb(0x78, 70, 80, 90), argb(0x9A, 100, 110, 120), argb(0xBC, 200, 10, 10),
                argb(0xDE, 0, 0, 0), argb(0xEF, 10, 240, 10), argb(0xFF, 0, 0, 0)
        };
        ImageBuffer src = new ImageBuffer(3, 3, pixels);
        ImageBuffer dst = src.cloneEmpty();

        ConvolutionOps.applyFull(src, dst, FilterType.SHARPEN);

        // Center (1,1): R=500-320=180, G=550-380=170, B=600-170=430->255, A=0x9A
        assertEquals(argb(0x9A, 180, 170, 255), dst.pixels[4]);

        // Corner (0,0) clamped top & left to (0,0): R=50-130->0, G=100-170->0, B=150-210->0, A=0x12
        assertEquals(argb(0x12, 0, 0, 0), dst.pixels[0]);

        // Right edge (2,1) clamped right to (2,1): R=1000-300=700->255, G=50-120->0, B=50-130->0, A=0xBC
        assertEquals(argb(0xBC, 255, 0, 0), dst.pixels[5]);
    }

    @Test
    void testSobel3x3ExactLuminanceIsqrtClampAndAlpha() {
        // Gentle gradient to verify exact non-saturated magnitude: val = x*20 + y*10
        ImageBuffer grad = new ImageBuffer(3, 3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                int v = x * 20 + y * 10;
                grad.pixels[y * 3 + x] = argb(0x40 + y * 3 + x, v, v, v);
            }
        }
        ImageBuffer gradDst = grad.cloneEmpty();
        ConvolutionOps.applyFull(grad, gradDst, FilterType.SOBEL);

        // At (1,1): gx = 160, gy = 80 -> isqrt(32000) = 178, alpha = 0x44
        assertEquals(argb(0x44, 178, 178, 178), gradDst.pixels[4]);

        // High-contrast matrix to verify luminance formula (77*r + 150*g + 29*b)>>8 and upper clamp 255
        int[] highPixels = {
                argb(0x11, 10, 20, 30), argb(0x22, 100, 150, 200), argb(0x33, 255, 255, 255),
                argb(0x44, 0, 0, 0), argb(0x55, 50, 60, 70), argb(0x66, 200, 100, 50),
                argb(0x77, 255, 0, 0), argb(0x88, 0, 255, 0), argb(0x99, 0, 0, 255)
        };
        ImageBuffer high = new ImageBuffer(3, 3, highPixels);
        ImageBuffer highDst = high.cloneEmpty();
        ConvolutionOps.applyFull(high, highDst, FilterType.SOBEL);

        // At (1,1): gx=437, gy=-151 -> isqrt(213770)=462 -> clamp255=255, alpha=0x55
        assertEquals(argb(0x55, 255, 255, 255), highDst.pixels[4]);
    }

    @Test
    void testPixelVerifierExactAndSingleDiff() {
        ImageBuffer expected = new ImageBuffer(4, 3);
        ImageBuffer actual = new ImageBuffer(4, 3);
        for (int i = 0; i < expected.pixels.length; i++) {
            int c = argb(0xFF, i * 10, i * 15, i * 20);
            expected.pixels[i] = c;
            actual.pixels[i] = c;
        }

        PixelVerifier.Result match = PixelVerifier.verify(expected, actual);
        assertTrue(match.exactMatch());
        assertEquals(12L, match.totalPixels());
        assertEquals(0L, match.diffPixels());

        // Alter pixel at (x=2, y=1) -> index 6
        int expectedColor = expected.pixels[6];
        int actualColor = argb(0xFF, 1, 2, 3);
        actual.pixels[6] = actualColor;

        PixelVerifier.Result mismatch = PixelVerifier.verify(expected, actual);
        assertFalse(mismatch.exactMatch());
        assertEquals(12L, mismatch.totalPixels());
        assertEquals(1L, mismatch.diffPixels());
        assertEquals(2, mismatch.firstX());
        assertEquals(1, mismatch.firstY());
        assertEquals(expectedColor, mismatch.expectedArgb());
        assertEquals(actualColor, mismatch.actualArgb());
    }
}
