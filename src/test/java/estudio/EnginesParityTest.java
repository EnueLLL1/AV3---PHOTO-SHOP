package estudio;

import estudio.core.DatasetGenerator;
import estudio.core.PixelVerifier;
import estudio.engine.ImageEngine;
import estudio.engine.MpjSubprocessEngine;
import estudio.engine.OpenClGpuEngine;
import estudio.engine.SequentialEngine;
import estudio.engine.ThreadedBlocksEngine;
import estudio.engine.ThreadedRowsEngine;
import estudio.model.FilterType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnginesParityTest {

    @Test
    void testCpuAndMpjEnginesMatchSequentialBitForBit(@TempDir Path tempDir) throws Exception {
        Path inputDir = tempDir.resolve("input");
        List<Path> inputs = DatasetGenerator.generateDataset(inputDir, 2, 64, 48);
        assertEquals(2, inputs.size());

        ImageEngine sequential = new SequentialEngine();
        ImageEngine rows = new ThreadedRowsEngine(3);
        ImageEngine blocks = new ThreadedBlocksEngine(3, 17);
        ImageEngine mpj = new MpjSubprocessEngine(2, "multicore");

        for (FilterType filter : FilterType.values()) {
            Path filterRoot = tempDir.resolve(filter.name().toLowerCase(Locale.ROOT));
            Path seqOut = filterRoot.resolve("seq");
            sequential.process(inputs, seqOut, filter, null);
            List<Path> seqFiles = listSortedPngs(seqOut);
            assertEquals(2, seqFiles.size());

            assertEngineParity("THREADS_ROWS", rows, inputs, seqFiles, filterRoot.resolve("rows"), filter);
            assertEngineParity("THREADS_BLOCKS", blocks, inputs, seqFiles, filterRoot.resolve("blocks"), filter);
            assertEngineParity("MPJ_MULTICORE", mpj, inputs, seqFiles, filterRoot.resolve("mpj"), filter);
        }
    }

    @Test
    void testOpenClGpuEngineMatchesSequentialWhenAvailable(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(OpenClGpuEngine.isOpenClAvailable(), "OpenCL unavailable on this host");

        Path inputDir = tempDir.resolve("input");
        List<Path> inputs = DatasetGenerator.generateDataset(inputDir, 2, 64, 48);

        ImageEngine sequential = new SequentialEngine();
        ImageEngine gpu = new OpenClGpuEngine();

        for (FilterType filter : FilterType.values()) {
            Path filterRoot = tempDir.resolve(filter.name().toLowerCase(Locale.ROOT));
            Path seqOut = filterRoot.resolve("seq");
            sequential.process(inputs, seqOut, filter, null);
            List<Path> seqFiles = listSortedPngs(seqOut);

            assertEngineParity("GPU_OPENCL", gpu, inputs, seqFiles, filterRoot.resolve("gpu"), filter);
        }
    }

    private static void assertEngineParity(
            String label,
            ImageEngine engine,
            List<Path> inputs,
            List<Path> expectedFiles,
            Path outDir,
            FilterType filter) throws Exception {
        engine.process(inputs, outDir, filter, null);
        List<Path> actualFiles = listSortedPngs(outDir);
        PixelVerifier.Result result = PixelVerifier.verifyDirectories(expectedFiles, actualFiles);
        assertTrue(result.exactMatch(),
                label + " mismatch for " + filter + ": diffPixels=" + result.diffPixels()
                        + " at (" + result.firstX() + "," + result.firstY() + ")");
        assertEquals(0L, result.diffPixels());
    }

    private static List<Path> listSortedPngs(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }
}
