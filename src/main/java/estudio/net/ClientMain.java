package estudio.net;

import estudio.core.DatasetGenerator;
import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.model.JobTicket;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

public class ClientMain {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_RMI_PORT = 1099;
    private static final ExecutionMode DEFAULT_MODE = ExecutionMode.BENCHMARK_ALL;
    private static final FilterType DEFAULT_FILTER = FilterType.BLUR;
    private static final Path DEFAULT_OUTPUT = Path.of("work", "client_out");
    private static final int DEFAULT_THREADS = 0;
    private static final int DEFAULT_TILE_SIZE = 128;
    private static final int DEFAULT_MPI_NP = 4;

    public static void main(String[] args) throws Exception {
        String host = DEFAULT_HOST;
        int rmiPort = DEFAULT_RMI_PORT;
        ExecutionMode mode = DEFAULT_MODE;
        FilterType filter = DEFAULT_FILTER;
        Path inputDir = null;
        Path outputDir = DEFAULT_OUTPUT;
        int threads = DEFAULT_THREADS;
        int tileSize = DEFAULT_TILE_SIZE;
        int mpiNp = DEFAULT_MPI_NP;
        boolean generate = false;
        int count = DatasetGenerator.DEFAULT_COUNT;
        int width = DatasetGenerator.DEFAULT_WIDTH;
        int height = DatasetGenerator.DEFAULT_HEIGHT;
        int concurrentDemo = 1;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--host" -> host = requireValue(args, ++i, "--host");
                case "--rmi-port" -> rmiPort = Integer.parseInt(requireValue(args, ++i, "--rmi-port"));
                case "--mode" -> mode = ExecutionMode.fromString(requireValue(args, ++i, "--mode"));
                case "--filter" -> filter = FilterType.fromString(requireValue(args, ++i, "--filter"));
                case "--input" -> inputDir = Path.of(requireValue(args, ++i, "--input"));
                case "--output" -> outputDir = Path.of(requireValue(args, ++i, "--output"));
                case "--threads" -> threads = Integer.parseInt(requireValue(args, ++i, "--threads"));
                case "--tile-size" -> tileSize = Integer.parseInt(requireValue(args, ++i, "--tile-size"));
                case "--mpi-np" -> mpiNp = Integer.parseInt(requireValue(args, ++i, "--mpi-np"));
                case "--generate" -> generate = true;
                case "--count" -> count = Integer.parseInt(requireValue(args, ++i, "--count"));
                case "--width" -> width = Integer.parseInt(requireValue(args, ++i, "--width"));
                case "--height" -> height = Integer.parseInt(requireValue(args, ++i, "--height"));
                case "--concurrent-demo" -> concurrentDemo = Integer.parseInt(requireValue(args, ++i, "--concurrent-demo"));
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        if (concurrentDemo < 1) {
            throw new IllegalArgumentException("--concurrent-demo must be >= 1: " + concurrentDemo);
        }

        List<Path> inputFiles = prepareInputFiles(generate, inputDir, outputDir, count, width, height);
        JobRequest request = new JobRequest(filter, mode, threads, tileSize, mpiNp);

        if (concurrentDemo == 1) {
            runSingleClient("client-1", host, rmiPort, request, inputFiles, outputDir);
        } else {
            runConcurrentDemo(concurrentDemo, host, rmiPort, request, inputFiles, outputDir);
        }
    }

    private static List<Path> prepareInputFiles(
            boolean generate,
            Path inputDir,
            Path outputDir,
            int count,
            int width,
            int height) throws IOException {
        if (generate) {
            Path genDir = inputDir != null ? inputDir : outputDir.resolve("_generated_input");
            System.out.printf("Generating %d synthetic images (%dx%d) into %s...%n",
                    count, width, height, genDir.toAbsolutePath().normalize());
            return DatasetGenerator.generateDataset(genDir, count, width, height);
        }
        if (inputDir == null) {
            throw new IllegalArgumentException("Either --input <dir> or --generate must be provided (use --help)");
        }
        if (!Files.isDirectory(inputDir)) {
            throw new IllegalArgumentException("Input directory does not exist: " + inputDir);
        }
        List<Path> files = listSupportedImages(inputDir);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No supported image files (.png/.jpg/.jpeg/.bmp) found in: " + inputDir);
        }
        return files;
    }

    private static void runConcurrentDemo(
            int clients,
            String host,
            int rmiPort,
            JobRequest request,
            List<Path> inputFiles,
            Path outputRoot) throws Exception {
        System.out.printf("Starting concurrent demo with %d simultaneous clients against %s:%d...%n",
                clients, host, rmiPort);

        CountDownLatch readyGate = new CountDownLatch(clients);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(clients);

        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 1; i <= clients; i++) {
                final String clientTag = "client-" + i;
                final Path clientOut = outputRoot.resolve(clientTag);
                futures.add(pool.submit(() -> {
                    readyGate.countDown();
                    startGate.await();
                    runSingleClient(clientTag, host, rmiPort, request, inputFiles, clientOut);
                    return null;
                }));
            }

            readyGate.await();
            startGate.countDown();

            for (Future<?> f : futures) {
                f.get();
            }
        }
        System.out.println("Concurrent demo finished: all " + clients + " clients completed.");
    }

    private static void runSingleClient(
            String clientTag,
            String host,
            int rmiPort,
            JobRequest request,
            List<Path> inputFiles,
            Path outputDir) throws Exception {
        Files.createDirectories(outputDir);

        Registry registry = LocateRegistry.getRegistry(host, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) registry.lookup(CoordinatorRmiService.BINDING_NAME);
        String pingResp = rmi.ping();
        JobTicket ticket = rmi.submitJob(request);

        System.out.printf("[%s] RMI %s | jobId=%s | queuePos=%d | tcp=%s:%d%n",
                clientTag, pingResp, ticket.getJobId(), ticket.getQueuePosition(),
                ticket.getSocketHost(), ticket.getSocketPort());

        int receivedImages = 0;
        TcpProtocol.ReportFrame reportFrame = null;

        try (Socket socket = new Socket(ticket.getSocketHost(), ticket.getSocketPort());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

            TcpProtocol.sendUploadBatch(out, ticket.getJobId(), inputFiles);

            while (true) {
                byte frameType = in.readByte();
                switch (frameType) {
                    case TcpProtocol.FRAME_PROGRESS -> {
                        TcpProtocol.ProgressFrame p = TcpProtocol.readProgressPayload(in);
                        printProgress(clientTag, p);
                    }
                    case TcpProtocol.FRAME_REPORT -> {
                        reportFrame = TcpProtocol.readReportPayload(in);
                        Files.writeString(outputDir.resolve("benchmark_report.txt"),
                                reportFrame.tableText(), StandardCharsets.UTF_8);
                        Files.writeString(outputDir.resolve("benchmark_report.csv"),
                                reportFrame.csvText(), StandardCharsets.UTF_8);
                    }
                    case TcpProtocol.FRAME_IMAGE -> {
                        TcpProtocol.readImageToDir(in, outputDir);
                        receivedImages++;
                    }
                    case TcpProtocol.FRAME_DONE -> {
                        String doneMsg = TcpProtocol.readDonePayload(in);
                        System.out.printf("[%s] %s | Imagens salvas: %d em %s%n",
                                clientTag, doneMsg, receivedImages, outputDir.toAbsolutePath().normalize());
                        if (reportFrame != null) {
                            synchronized (System.out) {
                                System.out.println("=== [" + clientTag + "] Resultado ===");
                                System.out.print(reportFrame.tableText());
                                System.out.printf("[%s] Verificacao global: %s (diffPixels=%d)%n",
                                        clientTag, reportFrame.verified() ? "OK" : "COM DIVERGENCIAS/FALHAS",
                                        reportFrame.diffPixels());
                            }
                        }
                        return;
                    }
                    case TcpProtocol.FRAME_ERROR -> {
                        String err = TcpProtocol.readErrorPayload(in);
                        throw new IOException("[" + clientTag + "] Server error: " + err);
                    }
                    default -> throw new IOException(
                            "[" + clientTag + "] Unknown TCP frame type: 0x"
                                    + String.format(Locale.ROOT, "%02X", frameType));
                }
            }
        }
    }

    private static void printProgress(String clientTag, TcpProtocol.ProgressFrame p) {
        int total = Math.max(1, p.totalSteps());
        int completed = Math.min(total, Math.max(0, p.completedSteps()));
        int pct = (int) Math.round((completed * 100.0) / total);
        int barWidth = 20;
        int filled = (completed * barWidth) / total;
        String bar = "#".repeat(filled) + "-".repeat(barWidth - filled);
        System.out.printf("[%s] [%s] %3d%% (%d/%d) %-7s - %s%n",
                clientTag, bar, pct, completed, total, p.state(), p.message());
    }

    private static List<Path> listSupportedImages(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(ClientMain::isSupportedImage)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".bmp");
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[idx];
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java ... estudio.net.ClientMain [options]
                  --host <hostname>          Coordinator host (default: 127.0.0.1)
                  --rmi-port <port>          Coordinator RMI port (default: 1099)
                  --mode <mode>              SEQUENTIAL | THREADS_ROWS | THREADS_BLOCKS | MPJ | GPU | BENCHMARK_ALL (default: BENCHMARK_ALL)
                  --filter <filter>          BLUR | SHARPEN | SOBEL (default: BLUR)
                  --input <dir>              Input directory of images
                  --output <dir>             Output directory (default: work/client_out)
                  --threads <n>              Worker threads for THREADS_* modes, 0=auto (default: 0)
                  --tile-size <px>           Tile size for THREADS_BLOCKS (default: 128)
                  --mpi-np <n>               MPJ process count (default: 4)
                  --generate                 Generate synthetic dataset before submitting
                  --count <n>                Synthetic image count (default: 20)
                  --width <px>               Synthetic image width (default: 4000)
                  --height <px>              Synthetic image height (default: 3000)
                  --concurrent-demo <n>      Launch N simultaneous clients to demonstrate queue/concurrency (default: 1)
                  --help, -h                 Show this help
                """);
    }
}
