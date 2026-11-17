package estudio.engine;

import estudio.core.ConvolutionOps;
import estudio.core.ImageBuffer;
import estudio.model.FilterType;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ThreadedRowsEngine implements ImageEngine {
    private final int threads;

    public ThreadedRowsEngine(int threads) {
        this.threads = threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
    }

    @Override
    public EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception {
        String details = "Threads by row strips (" + threads + " workers), filter=" + filter;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            return BatchEngineSupport.runBatch(
                    "THREADS_ROWS",
                    threads,
                    details,
                    inputFiles,
                    outputDir,
                    filter,
                    listener,
                    (src, dst, f) -> applyByRows(executor, src, dst, f));
        }
    }

    private void applyByRows(ExecutorService executor, ImageBuffer src, ImageBuffer dst, FilterType filter)
            throws Exception {
        int height = src.height;
        int taskCount = Math.min(threads, height);
        List<Future<?>> futures = BatchEngineSupport.newFutureList(taskCount);

        for (int t = 0; t < taskCount; t++) {
            int yStart = (int) ((long) t * height / taskCount);
            int yEnd = (int) ((long) (t + 1) * height / taskCount);
            futures.add(executor.submit(() -> ConvolutionOps.applyRows(src, dst, filter, yStart, yEnd)));
        }

        BatchEngineSupport.awaitAll(futures);
    }
}
