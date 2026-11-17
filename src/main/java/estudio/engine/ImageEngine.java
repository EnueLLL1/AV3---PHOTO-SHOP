package estudio.engine;

import estudio.model.FilterType;

import java.nio.file.Path;
import java.util.List;

public interface ImageEngine {
    @FunctionalInterface
    interface ProgressListener {
        void onProgress(int step, int total, String detail);
    }

    record EngineResult(
            String engineName,
            int workers,
            long computeMs,
            long totalMs,
            String details) {
    }

    EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception;
}
