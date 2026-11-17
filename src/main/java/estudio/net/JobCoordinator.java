package estudio.net;

import estudio.core.ImageBuffer;
import estudio.core.PixelVerifier;
import estudio.engine.ImageEngine;
import estudio.engine.MpjSubprocessEngine;
import estudio.engine.OpenClGpuEngine;
import estudio.engine.SequentialEngine;
import estudio.engine.ThreadedBlocksEngine;
import estudio.engine.ThreadedRowsEngine;
import estudio.model.BenchmarkReport;
import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.model.JobStatus;
import estudio.model.JobTicket;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class JobCoordinator implements AutoCloseable {

    private sealed interface ServerEvent permits ProgressEvent, CompletedEvent, FailedEvent {
    }

    private record ProgressEvent(JobStatus.State state, int completedSteps, int totalSteps, String message)
            implements ServerEvent {
    }

    private record CompletedEvent(BenchmarkReport report, List<Path> outputFiles) implements ServerEvent {
    }

    private record FailedEvent(String errorMessage) implements ServerEvent {
    }

    private static final class JobEntry {
        final String jobId;
        final JobRequest request;
        final Path inputDir;
        final Path outputDir;
        final AtomicReference<JobStatus> status;
        final BlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
        final AtomicBoolean slotReleased = new AtomicBoolean(false);
        volatile List<Path> inputFiles = List.of();

        JobEntry(String jobId, JobRequest request, Path inputDir, Path outputDir, JobStatus initialStatus) {
            this.jobId = jobId;
            this.request = request;
            this.inputDir = inputDir;
            this.outputDir = outputDir;
            this.status = new AtomicReference<>(initialStatus);
        }
    }

    private final String socketHost;
    private final int socketPort;
    private final Path workRoot;
    private final String mpiDev;
    private final String mpiMachines;
    private final String mpiHome;
    private final String classpath;

    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workerPool;
    private final AtomicInteger queuedOrActive = new AtomicInteger(0);

    public JobCoordinator(
            String socketHost,
            int socketPort,
            int maxConcurrentJobs,
            Path workRoot,
            String mpiDev,
            String mpiMachines,
            String mpiHome,
            String classpath) {
        if (Objects.requireNonNull(socketHost, "socketHost must not be null").isBlank()) {
            throw new IllegalArgumentException("socketHost must not be blank");
        }
        if (socketPort <= 0 || socketPort > 65535) {
            throw new IllegalArgumentException("socketPort must be in [1, 65535]: " + socketPort);
        }
        if (maxConcurrentJobs <= 0) {
            throw new IllegalArgumentException("maxConcurrentJobs must be > 0: " + maxConcurrentJobs);
        }
        this.socketHost = socketHost.trim();
        this.socketPort = socketPort;
        this.workRoot = Objects.requireNonNull(workRoot, "workRoot must not be null").toAbsolutePath().normalize();
        this.mpiDev = mpiDev;
        this.mpiMachines = mpiMachines;
        this.mpiHome = mpiHome;
        this.classpath = classpath;

        this.workerPool = new ThreadPoolExecutor(
                maxConcurrentJobs,
                maxConcurrentJobs,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
    }

    public JobTicket submitJob(JobRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String jobId = UUID.randomUUID().toString();
        Path jobDir = workRoot.resolve("jobs").resolve(jobId);
        Path inputDir = jobDir.resolve("input");
        Path outputDir = jobDir.resolve("output");

        try {
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create workspace for job " + jobId, e);
        }

        JobStatus initialStatus = new JobStatus(
                jobId,
                JobStatus.State.AWAITING_UPLOAD,
                0,
                1,
                "Aguardando envio de imagens via TCP");
        JobEntry entry = new JobEntry(jobId, request, inputDir, outputDir, initialStatus);
        jobs.put(jobId, entry);

        int queuePosition = Math.max(0, queuedOrActive.getAndIncrement());
        return new JobTicket(jobId, socketHost, socketPort, queuePosition);
    }

    public JobStatus getJobStatus(String jobId) {
        if (Objects.requireNonNull(jobId, "jobId must not be null").isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        JobEntry entry = jobs.get(jobId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown jobId: " + jobId);
        }
        return entry.status.get();
    }

    public String ping() {
        return "PONG [host=" + socketHost
                + ", tcpPort=" + socketPort
                + ", maxConcurrent=" + workerPool.getMaximumPoolSize()
                + ", queuedOrActive=" + queuedOrActive.get() + "]";
    }

    public void handleTcpClient(Socket socket) {
        Objects.requireNonNull(socket, "socket must not be null");
        Path stagingDir = workRoot.resolve("staging").resolve(UUID.randomUUID().toString());
        JobEntry entry = null;
        boolean submittedToPool = false;

        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

            TcpProtocol.UploadBatch batch = TcpProtocol.readUploadBatch(in, stagingDir);
            entry = jobs.get(batch.jobId());
            if (entry == null) {
                TcpProtocol.sendError(out, "Unknown jobId: " + batch.jobId());
                return;
            }

            JobStatus current = entry.status.get();
            int totalSteps = estimateTotalSteps(entry.request, batch.files().size());
            JobStatus queuedStatus = new JobStatus(
                    entry.jobId,
                    JobStatus.State.QUEUED,
                    0,
                    totalSteps,
                    "Lote recebido (" + batch.files().size() + " imagens); na fila");
            if (current.getState() != JobStatus.State.AWAITING_UPLOAD
                    || !entry.status.compareAndSet(current, queuedStatus)) {
                TcpProtocol.sendError(out, "Job " + entry.jobId + " is not awaiting upload (state=" + current.getState() + ")");
                return;
            }

            List<Path> stagedSorted = new ArrayList<>(batch.files());
            stagedSorted.sort(Comparator.comparing(p -> p.getFileName().toString()));

            List<Path> inputFiles = new ArrayList<>(stagedSorted.size());
            for (Path stagedFile : stagedSorted) {
                Path target = entry.inputDir.resolve(stagedFile.getFileName().toString());
                Files.move(stagedFile, target, StandardCopyOption.REPLACE_EXISTING);
                inputFiles.add(target);
            }
            entry.inputFiles = List.copyOf(inputFiles);

            final JobEntry jobToRun = entry;
            try {
                workerPool.submit(() -> executeJob(jobToRun));
                submittedToPool = true;
            } catch (RejectedExecutionException rejected) {
                markFailedBeforeExecution(entry, "Coordinator is shutting down");
                TcpProtocol.sendError(out, "Coordinator is shutting down");
                return;
            }

            TcpProtocol.sendProgress(out, JobStatus.State.QUEUED, 0, totalSteps, queuedStatus.getMessage());

            while (true) {
                ServerEvent event = entry.events.take();
                switch (event) {
                    case ProgressEvent p -> TcpProtocol.sendProgress(
                            out, p.state(), p.completedSteps(), p.totalSteps(), p.message());
                    case CompletedEvent c -> {
                        TcpProtocol.sendReport(out, c.report());
                        for (Path outFile : c.outputFiles()) {
                            TcpProtocol.sendImage(out, outFile);
                        }
                        TcpProtocol.sendDone(out, "Job " + entry.jobId + " finalizado (" + c.outputFiles().size() + " imagens)");
                        return;
                    }
                    case FailedEvent f -> {
                        TcpProtocol.sendError(out, f.errorMessage());
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (entry != null && !submittedToPool) {
                markFailedBeforeExecution(entry, "Interrupted during upload");
            }
        } catch (Exception e) {
            if (entry != null && !submittedToPool) {
                markFailedBeforeExecution(entry, conciseMessage(e));
            }
            trySendErrorQuietly(socket, e);
        } finally {
            if (entry != null && !submittedToPool) {
                releaseSlot(entry);
            }
            deleteRecursively(stagingDir);
        }
    }

    private void executeJob(JobEntry entry) {
        JobRequest req = entry.request;
        List<Path> inputs = entry.inputFiles;
        int imageCount = inputs.size();
        int totalSteps = estimateTotalSteps(req, imageCount);

        try {
            int width = 0;
            int height = 0;
            if (!inputs.isEmpty()) {
                ImageBuffer sample = ImageBuffer.read(inputs.get(0));
                width = sample.width;
                height = sample.height;
            }

            emitProgress(entry, JobStatus.State.RUNNING, 0, totalSteps,
                    "Executando modo " + req.getMode() + " (" + req.getFilter() + ")");

            BenchmarkReport report;
            List<Path> finalOutputFiles;

            if (req.getMode() == ExecutionMode.BENCHMARK_ALL) {
                BenchmarkOutcome outcome = runBenchmarkAll(entry, inputs, width, height, totalSteps);
                report = outcome.report();
                finalOutputFiles = outcome.outputFiles();
            } else {
                SingleOutcome outcome = runSingleMode(entry, inputs, width, height, totalSteps);
                report = outcome.report();
                finalOutputFiles = outcome.outputFiles();
            }

            JobStatus doneStatus = new JobStatus(
                    entry.jobId,
                    JobStatus.State.COMPLETED,
                    totalSteps,
                    totalSteps,
                    "Processamento concluido");
            entry.status.set(doneStatus);
            entry.events.offer(new CompletedEvent(report, finalOutputFiles));
        } catch (Throwable t) {
            String reason = "Job falhou: " + conciseMessage(t);
            entry.status.set(new JobStatus(entry.jobId, JobStatus.State.FAILED, 0, totalSteps, reason));
            entry.events.offer(new FailedEvent(reason));
        } finally {
            releaseSlot(entry);
        }
    }

    private record SingleOutcome(BenchmarkReport report, List<Path> outputFiles) {
    }

    private record BenchmarkOutcome(BenchmarkReport report, List<Path> outputFiles) {
    }

    private SingleOutcome runSingleMode(
            JobEntry entry,
            List<Path> inputs,
            int width,
            int height,
            int totalSteps) throws Exception {
        JobRequest req = entry.request;
        ExecutionMode mode = req.getMode();
        FilterType filter = req.getFilter();
        Path modeOutDir = entry.outputDir.resolve(mode.name().toLowerCase(Locale.ROOT));

        ImageEngine engine = createEngine(mode, req);
        ImageEngine.EngineResult res = engine.process(
                inputs,
                modeOutDir,
                filter,
                (step, total, detail) -> emitProgress(
                        entry,
                        JobStatus.State.RUNNING,
                        step,
                        Math.max(1, total),
                        mode.name() + ": " + detail));

        boolean isSeq = mode == ExecutionMode.SEQUENTIAL;
        double speedup = isSeq ? 1.0 : 0.0;
        double efficiency = isSeq ? 100.0 : 0.0;
        String notes = isSeq ? "Sequencial (baseline)" : res.details() + " (single-mode)";

        BenchmarkReport.Entry reportEntry = new BenchmarkReport.Entry(
                res.engineName(),
                res.workers(),
                res.computeMs(),
                res.totalMs(),
                speedup,
                efficiency,
                0L,
                true,
                notes);

        BenchmarkReport report = new BenchmarkReport(filter, inputs.size(), width, height, List.of(reportEntry));
        List<Path> outputs = listSortedPngs(modeOutDir);
        emitProgress(entry, JobStatus.State.RUNNING, totalSteps, totalSteps, mode.name() + " concluido");
        return new SingleOutcome(report, outputs);
    }

    private BenchmarkOutcome runBenchmarkAll(
            JobEntry entry,
            List<Path> inputs,
            int width,
            int height,
            int totalSteps) throws Exception {
        JobRequest req = entry.request;
        FilterType filter = req.getFilter();
        int perEngineSteps = Math.max(1, inputs.size());
        List<BenchmarkReport.Entry> entries = new ArrayList<>(5);

        // 1. SEQUENTIAL (baseline obrigatoria)
        Path seqOutDir = entry.outputDir.resolve("seq");
        emitProgress(entry, JobStatus.State.RUNNING, 0, totalSteps, "Iniciando SEQUENTIAL (baseline)");
        ImageEngine.EngineResult seqRes = new SequentialEngine().process(
                inputs,
                seqOutDir,
                filter,
                (step, total, detail) -> emitProgress(
                        entry,
                        JobStatus.State.RUNNING,
                        step,
                        totalSteps,
                        "SEQUENTIAL: " + detail));

        List<Path> seqFiles = listSortedPngs(seqOutDir);
        long seqComputeMs = seqRes.computeMs();
        entries.add(new BenchmarkReport.Entry(
                "SEQUENTIAL",
                1,
                seqComputeMs,
                seqRes.totalMs(),
                1.0,
                100.0,
                0L,
                true,
                "Baseline sequencial"));

        List<Path> verifiedOutputFiles = seqFiles;

        // 2..5. Motores paralelos/distribuidos comparados contra SEQUENTIAL
        record StageSpec(ExecutionMode mode, String label, String subDir, int fallbackWorkers) {
        }

        List<StageSpec> stages = List.of(
                new StageSpec(ExecutionMode.THREADS_ROWS, "THREADS_ROWS", "rows", req.getThreads()),
                new StageSpec(ExecutionMode.THREADS_BLOCKS, "THREADS_BLOCKS", "blocks", req.getThreads()),
                new StageSpec(ExecutionMode.MPJ, "MPJ_" + normalizeMpiDevLabel(), "mpj", req.getMpiProcesses()),
                new StageSpec(ExecutionMode.GPU, OpenClGpuEngine.ENGINE_NAME, "gpu", 1)
        );

        for (int idx = 0; idx < stages.size(); idx++) {
            StageSpec stage = stages.get(idx);
            int stepOffset = (idx + 1) * perEngineSteps;
            Path stageOutDir = entry.outputDir.resolve(stage.subDir());
            emitProgress(entry, JobStatus.State.RUNNING, stepOffset, totalSteps, "Iniciando " + stage.label());

            try {
                ImageEngine engine = createEngine(stage.mode(), req);
                ImageEngine.EngineResult res = engine.process(
                        inputs,
                        stageOutDir,
                        filter,
                        (step, total, detail) -> emitProgress(
                                entry,
                                JobStatus.State.RUNNING,
                                Math.min(totalSteps, stepOffset + step),
                                totalSteps,
                                stage.label() + ": " + detail));

                List<Path> stageFiles = listSortedPngs(stageOutDir);
                PixelVerifier.Result verifyResult = PixelVerifier.verifyDirectories(seqFiles, stageFiles);

                double speedup = seqComputeMs / (double) Math.max(1L, res.computeMs());
                int workers = res.workers();
                double efficiencyPct = workers > 0 ? (speedup / workers) * 100.0 : 0.0;

                String notes = res.details();
                if (stage.mode() == ExecutionMode.GPU) {
                    notes = res.details() + " | CUs=" + workers + " (eficiencia por CU)";
                }

                entries.add(new BenchmarkReport.Entry(
                        res.engineName(),
                        workers,
                        res.computeMs(),
                        res.totalMs(),
                        speedup,
                        efficiencyPct,
                        verifyResult.diffPixels(),
                        verifyResult.exactMatch(),
                        notes));

                if (verifyResult.exactMatch() && !stageFiles.isEmpty()) {
                    verifiedOutputFiles = stageFiles;
                }
            } catch (Exception | LinkageError ex) {
                String note = "Indisponivel: " + conciseMessage(ex);
                entries.add(new BenchmarkReport.Entry(
                        stage.label(),
                        stage.fallbackWorkers(),
                        0L,
                        0L,
                        0.0,
                        0.0,
                        0L,
                        false,
                        note));
                emitProgress(
                        entry,
                        JobStatus.State.RUNNING,
                        Math.min(totalSteps, stepOffset + perEngineSteps),
                        totalSteps,
                        stage.label() + " indisponivel: " + conciseMessage(ex));
            }
        }

        BenchmarkReport report = new BenchmarkReport(filter, inputs.size(), width, height, entries);
        return new BenchmarkOutcome(report, verifiedOutputFiles);
    }

    private ImageEngine createEngine(ExecutionMode mode, JobRequest req) {
        return switch (mode) {
            case SEQUENTIAL -> new SequentialEngine();
            case THREADS_ROWS -> new ThreadedRowsEngine(req.getThreads());
            case THREADS_BLOCKS -> new ThreadedBlocksEngine(req.getThreads(), req.getTileSize());
            case MPJ -> new MpjSubprocessEngine(req.getMpiProcesses(), mpiDev, mpiMachines, mpiHome, classpath);
            case GPU -> new OpenClGpuEngine();
            case BENCHMARK_ALL -> throw new IllegalArgumentException("BENCHMARK_ALL is not a single engine mode");
        };
    }

    private String normalizeMpiDevLabel() {
        if (mpiDev == null || mpiDev.isBlank()) {
            return "MULTICORE";
        }
        return mpiDev.trim().toUpperCase(Locale.ROOT);
    }

    private static int estimateTotalSteps(JobRequest req, int imageCount) {
        int base = Math.max(1, imageCount);
        return req.getMode() == ExecutionMode.BENCHMARK_ALL ? 5 * base : base;
    }

    private void emitProgress(JobEntry entry, JobStatus.State state, int completed, int total, String message) {
        entry.status.set(new JobStatus(entry.jobId, state, completed, total, message));
        entry.events.offer(new ProgressEvent(state, completed, total, message));
    }

    private void markFailedBeforeExecution(JobEntry entry, String message) {
        entry.status.set(new JobStatus(entry.jobId, JobStatus.State.FAILED, 0, 1, message));
    }

    private void releaseSlot(JobEntry entry) {
        if (entry.slotReleased.compareAndSet(false, true)) {
            queuedOrActive.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    private static List<Path> listSortedPngs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static void trySendErrorQuietly(Socket socket, Exception error) {
        if (socket.isClosed() || socket.isOutputShutdown()) {
            return;
        }
        try {
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            TcpProtocol.sendError(out, conciseMessage(error));
        } catch (IOException ignored) {
        }
    }

    private static String conciseMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        String singleLine = msg.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() > 160 ? singleLine.substring(0, 157) + "..." : singleLine;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
        }
    }
}
