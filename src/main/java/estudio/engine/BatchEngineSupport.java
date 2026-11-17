package estudio.engine;

import estudio.core.ImageBuffer;
import estudio.model.FilterType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class BatchEngineSupport {

    @FunctionalInterface
    interface ImageProcessor {
        void apply(ImageBuffer src, ImageBuffer dst, FilterType filter) throws Exception;
    }

    private BatchEngineSupport() {
    }

    static String outputFileName(Path inputFile) {
        String name = inputFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".png";
    }

    static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    static List<Future<?>> newFutureList(int capacity) {
        return new ArrayList<>(capacity);
    }

    static ImageEngine.EngineResult runBatch(
            String engineName,
            int workers,
            String details,
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ImageEngine.ProgressListener listener,
            ImageProcessor processor) throws Exception {
        Objects.requireNonNull(inputFiles, "inputFiles must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(processor, "processor must not be null");

        Files.createDirectories(outputDir);

        int total = inputFiles.size();
        long totalStartNs = System.nanoTime();
        long computeNs = 0L;

        for (int i = 0; i < total; i++) {
            Path inputFile = Objects.requireNonNull(inputFiles.get(i), "inputFiles element must not be null");
            ImageBuffer src = ImageBuffer.read(inputFile);
            ImageBuffer dst = src.cloneEmpty();

            long computeStartNs = System.nanoTime();
            processor.apply(src, dst, filter);
            computeNs += System.nanoTime() - computeStartNs;

            String outName = outputFileName(inputFile);
            dst.writePng(outputDir.resolve(outName));

            if (listener != null) {
                listener.onProgress(i + 1, total, outName);
            }
        }

        long totalNs = System.nanoTime() - totalStartNs;
        return new ImageEngine.EngineResult(
                engineName,
                workers,
                TimeUnit.NANOSECONDS.toMillis(computeNs),
                TimeUnit.NANOSECONDS.toMillis(totalNs),
                details);
    }
}
