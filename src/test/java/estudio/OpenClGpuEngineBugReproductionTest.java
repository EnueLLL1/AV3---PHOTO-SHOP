package estudio;

import estudio.engine.ImageEngine;
import estudio.engine.OpenClGpuEngine;
import estudio.model.FilterType;
import estudio.core.DatasetGenerator;
import org.jocl.cl_program;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenClGpuEngineBugReproductionTest {

    @Test
    void consecutiveProcessCallsReuseCompiledProgramAndReportSeparateKernelAndTransferTimes(
            @TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(OpenClGpuEngine.isOpenClAvailable(), "OpenCL unavailable on this host");

        Path inputDir = tempDir.resolve("input");
        DatasetGenerator.generateDataset(inputDir, 2, 32, 32);
        List<Path> inputs = listSortedPngs(inputDir);

        try (OpenClGpuEngine engine = new OpenClGpuEngine()) {
            ImageEngine.EngineResult first = engine.process(inputs, tempDir.resolve("out1"), FilterType.BLUR, null);
            cl_program firstProgram = findCachedProgram(engine);
            assertNotNull(firstProgram,
                    "OpenClGpuEngine must cache the compiled cl_program on the instance across process() calls");

            ImageEngine.EngineResult second = engine.process(inputs, tempDir.resolve("out2"), FilterType.BLUR, null);
            cl_program secondProgram = findCachedProgram(engine);
            assertSame(firstProgram, secondProgram,
                    "Consecutive process() calls on the same OpenClGpuEngine instance must reuse the compiled cl_program");

            String detailsLower = (first.details() + " " + second.details()).toLowerCase(Locale.ROOT);
            assertTrue(detailsLower.contains("kernel"),
                    "EngineResult.details must report kernel time separately, got: " + second.details());
            assertTrue(detailsLower.contains("transfer"),
                    "EngineResult.details must report host<->device transfer time separately, got: " + second.details());
        }
    }

    private static cl_program findCachedProgram(OpenClGpuEngine engine) throws IllegalAccessException {
        for (Field field : OpenClGpuEngine.class.getDeclaredFields()) {
            if (cl_program.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return (cl_program) field.get(engine);
            }
        }
        return null;
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
