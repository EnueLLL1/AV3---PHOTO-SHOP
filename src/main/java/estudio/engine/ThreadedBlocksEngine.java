package estudio.engine;

import estudio.core.ConvolutionOps;
import estudio.core.ImageBuffer;
import estudio.model.FilterType;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class ThreadedBlocksEngine implements ImageEngine {
    private static final int DEFAULT_TILE_SIZE = 128;

    private final int threads;
    private final int tileSize;
    private volatile int[] lastRunTileCounts = new int[0];

    public ThreadedBlocksEngine(int threads, int tileSize) {
        this.threads = threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
        this.tileSize = tileSize <= 0 ? DEFAULT_TILE_SIZE : tileSize;
    }

    public int[] getLastRunTileCounts() {
        return lastRunTileCounts.clone();
    }

    @Override
    public EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception {
        String details = "Threads by 2D tiles (" + threads + " workers, tile=" + tileSize + "x" + tileSize
                + "), filter=" + filter;
        AtomicIntegerArray runCounts = new AtomicIntegerArray(threads);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            EngineResult result = BatchEngineSupport.runBatch(
                    "THREADS_BLOCKS",
                    threads,
                    details,
                    inputFiles,
                    outputDir,
                    filter,
                    listener,
                    (src, dst, f) -> applyByBlocks(executor, src, dst, f, runCounts));
            int[] snapshot = new int[threads];
            for (int i = 0; i < threads; i++) {
                snapshot[i] = runCounts.get(i);
            }
            this.lastRunTileCounts = snapshot;
            return result;
        }
    }

    private void applyByBlocks(
            ExecutorService executor,
            ImageBuffer src,
            ImageBuffer dst,
            FilterType filter,
            AtomicIntegerArray runCounts)
            throws Exception {
        int width = src.width;
        int height = src.height;
        int tilesX = Math.ceilDiv(width, tileSize);
        int tilesY = Math.ceilDiv(height, tileSize);
        int totalTiles = tilesX * tilesY;
        int workerCount = Math.min(threads, totalTiles);

        AtomicInteger nextTile = new AtomicInteger(0);
        List<Future<?>> futures = BatchEngineSupport.newFutureList(workerCount);

        for (int w = 0; w < workerCount; w++) {
            final int workerIndex = w;
            futures.add(executor.submit(() -> {
                int processed = 0;
                int idx;
                while ((idx = nextTile.getAndIncrement()) < totalTiles) {
                    int ty = idx / tilesX;
                    int tx = idx % tilesX;
                    int xStart = tx * tileSize;
                    int yStart = ty * tileSize;
                    int xEnd = Math.min(xStart + tileSize, width);
                    int yEnd = Math.min(yStart + tileSize, height);
                    ConvolutionOps.applyRegion(src, dst, filter, xStart, xEnd, yStart, yEnd);
                    processed++;
                }
                runCounts.addAndGet(workerIndex, processed);
            }));
        }

        BatchEngineSupport.awaitAll(futures);
    }
}
