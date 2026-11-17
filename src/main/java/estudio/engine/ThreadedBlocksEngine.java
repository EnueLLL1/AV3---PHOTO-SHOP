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

public class ThreadedBlocksEngine implements ImageEngine {
    private static final int DEFAULT_TILE_SIZE = 128;

    private final int threads;
    private final int tileSize;

    public ThreadedBlocksEngine(int threads, int tileSize) {
        this.threads = threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
        this.tileSize = tileSize <= 0 ? DEFAULT_TILE_SIZE : tileSize;
    }

    @Override
    public EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception {
        String details = "Threads by 2D tiles (" + threads + " workers, tile=" + tileSize + "x" + tileSize
                + "), filter=" + filter;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            return BatchEngineSupport.runBatch(
                    "THREADS_BLOCKS",
                    threads,
                    details,
                    inputFiles,
                    outputDir,
                    filter,
                    listener,
                    (src, dst, f) -> applyByBlocks(executor, src, dst, f));
        }
    }

    private void applyByBlocks(ExecutorService executor, ImageBuffer src, ImageBuffer dst, FilterType filter)
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
            futures.add(executor.submit(() -> {
                int idx;
                while ((idx = nextTile.getAndIncrement()) < totalTiles) {
                    int ty = idx / tilesX;
                    int tx = idx % tilesX;
                    int xStart = tx * tileSize;
                    int yStart = ty * tileSize;
                    int xEnd = Math.min(xStart + tileSize, width);
                    int yEnd = Math.min(yStart + tileSize, height);
                    ConvolutionOps.applyRegion(src, dst, filter, xStart, xEnd, yStart, yEnd);
                }
            }));
        }

        BatchEngineSupport.awaitAll(futures);
    }
}
