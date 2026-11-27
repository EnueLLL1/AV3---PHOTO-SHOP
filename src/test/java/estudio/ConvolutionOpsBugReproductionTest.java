package estudio;

import estudio.core.ConvolutionOps;
import estudio.core.ImageBuffer;
import estudio.model.FilterType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ConvolutionOpsBugReproductionTest {

    @Test
    void applyRegionRejectsSameInstanceForSrcAndDst() {
        ImageBuffer buffer = new ImageBuffer(8, 8);
        for (int i = 0; i < buffer.pixels.length; i++) {
            buffer.pixels[i] = 0xFF000000 | (i * 3);
        }

        for (FilterType filter : FilterType.values()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ConvolutionOps.applyRegion(buffer, buffer, filter, 0, buffer.width, 0, buffer.height),
                    "applyRegion must reject aliased src == dst for filter " + filter);
        }
    }
}
