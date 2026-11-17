package estudio.engine;

import estudio.core.ConvolutionOps;
import estudio.core.ImageBuffer;
import estudio.model.FilterType;
import mpi.MPI;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class MpjWorkerMain {
    private static final int TAG_SLICE_META = 100;
    private static final int TAG_SLICE_PIXELS = 101;
    private static final int TAG_RESULT_PIXELS = 200;

    private MpjWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        String[] appArgs = MPI.Init(args);
        int rank = MPI.COMM_WORLD.Rank();
        int size = MPI.COMM_WORLD.Size();

        try {
            List<Path> imageFiles = List.of();
            Path outputDir = null;
            Path metricsFile = null;
            int[] header = new int[3];

            if (rank == 0) {
                Path inputDir = Path.of(requireArg("--inputDir=", appArgs, args));
                outputDir = Path.of(requireArg("--outputDir=", appArgs, args));
                FilterType filter = FilterType.fromString(requireArg("--filter=", appArgs, args));
                String metricsArg = findArg("--metricsFile=", appArgs, args);
                if (metricsArg != null && !metricsArg.isBlank()) {
                    metricsFile = Path.of(metricsArg);
                }

                imageFiles = listSortedImages(inputDir);
                Files.createDirectories(outputDir);

                header[0] = imageFiles.size();
                header[1] = filter.ordinal();
                header[2] = filter.radius();
            }

            // Coletiva 1: difunde contagem de imagens, filtro e raio do halo
            MPI.COMM_WORLD.Bcast(header, 0, 3, MPI.INT, 0);

            int imageCount = header[0];
            FilterType filter = FilterType.values()[header[1]];
            int radius = header[2];

            long totalComputeNs = 0L;
            long totalKernelMaxNs = 0L;

            for (int i = 0; i < imageCount; i++) {
                ImageBuffer src = null;
                ImageBuffer dst = null;
                int[] dims = new int[2];

                if (rank == 0) {
                    src = ImageBuffer.read(imageFiles.get(i));
                    dst = src.cloneEmpty();
                    dims[0] = src.width;
                    dims[1] = src.height;
                }

                // Coletiva 2: difunde dimensoes da imagem atual
                MPI.COMM_WORLD.Bcast(dims, 0, 2, MPI.INT, 0);

                int width = dims[0];
                int height = dims[1];
                int startRow = (int) ((long) rank * height / size);
                int endRow = (int) ((long) (rank + 1) * height / size);
                int coreRows = endRow - startRow;

                // Coletiva 3: sincroniza todos os ranks antes de medir processamento + montagem
                MPI.COMM_WORLD.Barrier();
                long computeStartNs = (rank == 0) ? System.nanoTime() : 0L;

                int sliceRows = 0;
                int topOffset = 0;
                int[] slicePixels = null;
                ImageBuffer sliceDst = null;

                if (rank == 0) {
                    for (int r = 1; r < size; r++) {
                        int rStart = (int) ((long) r * height / size);
                        int rEnd = (int) ((long) (r + 1) * height / size);
                        int rCore = rEnd - rStart;
                        int haloStart = rCore > 0 ? Math.max(0, rStart - radius) : 0;
                        int haloEnd = rCore > 0 ? Math.min(height, rEnd + radius) : 0;
                        int rSlice = rCore > 0 ? (haloEnd - haloStart) : 0;
                        int rTopOffset = rCore > 0 ? (rStart - haloStart) : 0;

                        int[] meta = {rCore, rSlice, rTopOffset};
                        MPI.COMM_WORLD.Send(meta, 0, 3, MPI.INT, r, TAG_SLICE_META);
                        if (rSlice > 0) {
                            MPI.COMM_WORLD.Send(
                                    src.pixels,
                                    haloStart * width,
                                    rSlice * width,
                                    MPI.INT,
                                    r,
                                    TAG_SLICE_PIXELS);
                        }
                    }
                } else {
                    int[] meta = new int[3];
                    MPI.COMM_WORLD.Recv(meta, 0, 3, MPI.INT, 0, TAG_SLICE_META);
                    coreRows = meta[0];
                    sliceRows = meta[1];
                    topOffset = meta[2];
                    if (sliceRows > 0) {
                        slicePixels = new int[sliceRows * width];
                        MPI.COMM_WORLD.Recv(slicePixels, 0, sliceRows * width, MPI.INT, 0, TAG_SLICE_PIXELS);
                    }
                }

                long kernelStartNs = System.nanoTime();
                if (rank == 0) {
                    if (coreRows > 0) {
                        ConvolutionOps.applyRows(src, dst, filter, startRow, endRow);
                    }
                } else if (coreRows > 0 && sliceRows > 0) {
                    ImageBuffer sliceSrc = new ImageBuffer(width, sliceRows, slicePixels);
                    sliceDst = sliceSrc.cloneEmpty();
                    ConvolutionOps.applyRows(sliceSrc, sliceDst, filter, topOffset, topOffset + coreRows);
                }
                long localKernelNs = System.nanoTime() - kernelStartNs;

                // Ponto a ponto: ranks 1..size-1 enviam faixa util processada ao rank 0
                if (rank == 0) {
                    for (int r = 1; r < size; r++) {
                        int rStart = (int) ((long) r * height / size);
                        int rEnd = (int) ((long) (r + 1) * height / size);
                        int rCore = rEnd - rStart;
                        if (rCore > 0) {
                            MPI.COMM_WORLD.Recv(
                                    dst.pixels,
                                    rStart * width,
                                    rCore * width,
                                    MPI.INT,
                                    r,
                                    TAG_RESULT_PIXELS);
                        }
                    }
                    totalComputeNs += System.nanoTime() - computeStartNs;
                } else if (coreRows > 0 && sliceRows > 0) {
                    MPI.COMM_WORLD.Send(
                            sliceDst.pixels,
                            topOffset * width,
                            coreRows * width,
                            MPI.INT,
                            0,
                            TAG_RESULT_PIXELS);
                }

                // Coletiva 4: reduz o maior tempo de kernel entre todos os ranks
                long[] localKernelArr = {localKernelNs};
                long[] maxKernelArr = new long[1];
                MPI.COMM_WORLD.Reduce(localKernelArr, 0, maxKernelArr, 0, 1, MPI.LONG, MPI.MAX, 0);

                if (rank == 0) {
                    totalKernelMaxNs += maxKernelArr[0];
                    String outName = outputFileName(imageFiles.get(i));
                    dst.writePng(outputDir.resolve(outName));
                    System.out.printf(Locale.ROOT, "MPJ_PROGRESS %d %d %s%n", i + 1, imageCount, outName);
                }
            }

            if (rank == 0 && metricsFile != null) {
                long computeMs = TimeUnit.NANOSECONDS.toMillis(totalComputeNs);
                long kernelMaxMs = TimeUnit.NANOSECONDS.toMillis(totalKernelMaxNs);
                writeMetrics(metricsFile, computeMs, kernelMaxMs, size, imageCount, filter);
            }

            MPI.Finalize();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            try {
                MPI.COMM_WORLD.Abort(1);
            } catch (Throwable ignored) {
            }
            if (t instanceof Exception ex) {
                throw ex;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(t);
        }
    }

    static List<Path> listSortedImages(Path inputDir) throws IOException {
        if (!Files.isDirectory(inputDir)) {
            throw new IOException("inputDir is not a directory: " + inputDir);
        }
        try (Stream<Path> stream = Files.list(inputDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(MpjWorkerMain::isSupportedImageFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static boolean isSupportedImageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".bmp")
                || name.endsWith(".gif");
    }

    private static String outputFileName(Path inputFile) {
        String name = inputFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + ".png";
    }

    private static void writeMetrics(
            Path metricsFile,
            long computeMs,
            long kernelMaxMs,
            int workers,
            int images,
            FilterType filter) throws IOException {
        Path parent = metricsFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties props = new Properties();
        props.setProperty("computeMs", Long.toString(computeMs));
        props.setProperty("kernelMaxMs", Long.toString(kernelMaxMs));
        props.setProperty("workers", Integer.toString(workers));
        props.setProperty("images", Integer.toString(images));
        props.setProperty("filter", filter.name());
        try (Writer writer = Files.newBufferedWriter(metricsFile, StandardCharsets.UTF_8)) {
            props.store(writer, "MPJ Worker Metrics");
        }
    }

    private static String requireArg(String prefix, String[] appArgs, String[] rawArgs) {
        String value = findArg(prefix, appArgs, rawArgs);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument with prefix: " + prefix);
        }
        return value;
    }

    private static String findArg(String prefix, String[] appArgs, String[] rawArgs) {
        String fromApp = findArgInArray(prefix, appArgs);
        if (fromApp != null) {
            return fromApp;
        }
        return findArgInArray(prefix, rawArgs);
    }

    private static String findArgInArray(String prefix, String[] values) {
        if (values == null) {
            return null;
        }
        for (String arg : values) {
            if (arg != null && arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return null;
    }
}
