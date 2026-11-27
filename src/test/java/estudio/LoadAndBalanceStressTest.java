package estudio;

import estudio.core.DatasetGenerator;
import estudio.core.ImageBuffer;
import estudio.core.PixelVerifier;
import estudio.engine.ImageEngine;
import estudio.engine.OpenClGpuEngine;
import estudio.engine.SequentialEngine;
import estudio.engine.ThreadedBlocksEngine;
import estudio.model.BenchmarkReport;
import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.model.JobTicket;
import estudio.net.CoordinatorRmiImpl;
import estudio.net.CoordinatorRmiService;
import estudio.net.JobCoordinator;
import estudio.net.TcpProtocol;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("stress")
class LoadAndBalanceStressTest {

    private static final String HOST = "127.0.0.1";
    private static final int LARGE_REPORT_MIN_BYTES = 70 * 1024;

    @TempDir
    Path tempDir;

    @Test
    void mixedLoadJobCoordinatorRespectsMaxConcurrentAndReleasesAllSlots() throws Exception {
        final int maxConcurrent = 3;
        final long uploadTtlMs = 600L;
        final int validClients = 12;
        final int truncatedClients = 3;
        final int orphanClients = 3;
        final int totalClients = validClients + truncatedClients + orphanClients;

        Path inputDir = tempDir.resolve("s1-input");
        List<Path> inputs = DatasetGenerator.generateDataset(inputDir, 4, 256, 256);

        Path expectedDir = tempDir.resolve("s1-expected-seq");
        new SequentialEngine().process(inputs, expectedDir, FilterType.BLUR, null);
        List<Path> expectedFiles = listSortedPngs(expectedDir);
        assertEquals(4, expectedFiles.size());

        long startNs = System.nanoTime();
        try (CoordinatorHarness harness = new CoordinatorHarness(tempDir.resolve("s1-work"), maxConcurrent, uploadTtlMs)) {
            var trackedJobIds = ConcurrentHashMap.<String>newKeySet();
            var invalidJobIds = ConcurrentHashMap.<String>newKeySet();
            ThreadPoolExecutor workerPool = getWorkerPool(harness.coordinator);
            AtomicBoolean monitoring = new AtomicBoolean(true);
            AtomicInteger peakConcurrent = new AtomicInteger(0);

            Thread monitorThread = new Thread(() -> {
                while (monitoring.get()) {
                    peakConcurrent.accumulateAndGet(workerPool.getActiveCount(), Math::max);
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "stress-concurrency-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();

            CountDownLatch readyGate = new CountDownLatch(totalClients);
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<ValidOutcome>> validFutures = new ArrayList<>(validClients);
            List<Future<String>> failureFutures = new ArrayList<>(truncatedClients + orphanClients);

            try (ExecutorService clientPool = Executors.newFixedThreadPool(totalClients)) {
                for (int i = 0; i < validClients; i++) {
                    final int idx = i;
                    final ExecutionMode mode = (i % 2 == 0) ? ExecutionMode.THREADS_BLOCKS : ExecutionMode.THREADS_ROWS;
                    final JobRequest request = new JobRequest(FilterType.BLUR, mode, 4, 64, 2);
                    final Path clientOut = tempDir.resolve("s1-valid-out-" + idx);
                    validFutures.add(clientPool.submit(() -> {
                        readyGate.countDown();
                        startGate.await();
                        return runValidClient(harness.rmiPort, request, inputs, clientOut, trackedJobIds);
                    }));
                }

                for (int i = 0; i < truncatedClients; i++) {
                    final int idx = i;
                    final JobRequest request = new JobRequest(FilterType.BLUR, ExecutionMode.THREADS_BLOCKS, 2, 64, 1);
                    failureFutures.add(clientPool.submit(() -> {
                        readyGate.countDown();
                        startGate.await();
                        String jobId = runTruncatedClient(harness.rmiPort, request, idx, trackedJobIds);
                        invalidJobIds.add(jobId);
                        return jobId;
                    }));
                }

                for (int i = 0; i < orphanClients; i++) {
                    final JobRequest request = new JobRequest(FilterType.BLUR, ExecutionMode.THREADS_ROWS, 2, 64, 1);
                    failureFutures.add(clientPool.submit(() -> {
                        readyGate.countDown();
                        startGate.await();
                        String jobId = submitOrphanRmiJob(harness.rmiPort, request, trackedJobIds);
                        invalidJobIds.add(jobId);
                        return jobId;
                    }));
                }

                assertTrue(readyGate.await(10, TimeUnit.SECONDS), "All 18 stress clients must become ready");
                startGate.countDown();

                List<ValidOutcome> validOutcomes = new ArrayList<>(validClients);
                for (Future<ValidOutcome> f : validFutures) {
                    validOutcomes.add(f.get(120, TimeUnit.SECONDS));
                }
                for (Future<String> f : failureFutures) {
                    f.get(30, TimeUnit.SECONDS);
                }

                monitoring.set(false);
                monitorThread.join(2000L);

                int peak = peakConcurrent.get();
                int largestPoolSize = workerPool.getLargestPoolSize();
                assertTrue(peak >= 1, "Expected peakConcurrent >= 1, got " + peak);
                assertTrue(peak <= maxConcurrent,
                        "Observed active concurrency " + peak + " exceeded maxConcurrent=" + maxConcurrent);
                assertTrue(largestPoolSize >= 1,
                        "Expected workerPool.getLargestPoolSize() >= 1, got " + largestPoolSize);
                assertTrue(largestPoolSize <= maxConcurrent,
                        "workerPool.getLargestPoolSize()=" + largestPoolSize
                                + " exceeded maxConcurrent=" + maxConcurrent);

                for (ValidOutcome outcome : validOutcomes) {
                    assertTrue(outcome.doneReceived(), "Job " + outcome.jobId() + " did not receive FRAME_DONE");
                    assertNotNull(outcome.report(), "Job " + outcome.jobId() + " missing FRAME_REPORT");
                    assertEquals(inputs.size(), outcome.receivedFiles().size());

                    PixelVerifier.Result parity = PixelVerifier.verifyDirectories(expectedFiles, outcome.receivedFiles());
                    assertTrue(parity.exactMatch(),
                            "Pixel mismatch for valid job " + outcome.jobId() + ": diffPixels=" + parity.diffPixels());
                }

                AtomicInteger queuedOrActive = getQueuedOrActive(harness.coordinator);
                Map<?, ?> jobsMap = getJobsMap(harness.coordinator);

                long ttlDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < ttlDeadline) {
                    boolean allInvalidEvicted = invalidJobIds.stream().noneMatch(jobsMap::containsKey);
                    if (queuedOrActive.get() == 0
                            && harness.coordinator.ping().contains("queuedOrActive=0")
                            && allInvalidEvicted) {
                        break;
                    }
                    Thread.sleep(25L);
                }

                String pingAfter = harness.coordinator.ping();
                assertTrue(pingAfter.contains("queuedOrActive=0"),
                        "Expected ping() to report queuedOrActive=0 after TTL, got: " + pingAfter);
                assertEquals(0, queuedOrActive.get(),
                        "queuedOrActive leaked slots; final value=" + queuedOrActive.get());
                for (String invalidId : invalidJobIds) {
                    assertFalse(jobsMap.containsKey(invalidId),
                            "Truncated/orphan job " + invalidId + " remained in coordinator jobs map after TTL");
                }
                assertEquals(validClients, jobsMap.size(),
                        "Only completed valid jobs should remain in jobs map; size=" + jobsMap.size());

                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                System.out.printf(Locale.ROOT,
                        "[Scenario 1] elapsedMs=%d, clients=%d (valid=%d, truncated=%d, orphan=%d), "
                                + "peakConcurrent=%d/%d, largestPoolSize=%d, finalQueuedOrActive=%d, jobsMapSize=%d, ping=%s%n",
                        elapsedMs, totalClients, validClients, truncatedClients, orphanClients,
                        peak, maxConcurrent, largestPoolSize, queuedOrActive.get(), jobsMap.size(), pingAfter);
            } finally {
                monitoring.set(false);
                monitorThread.interrupt();
            }
        }
    }

    @Test
    void threadedBlocksEngineBalancesAsymmetricBatchAndMatchesSequential() throws Exception {
        int[][] dimensions = {
                {128, 128},
                {192, 160},
                {256, 192},
                {1024, 768},
                {1280, 960}
        };
        final int threads = 4;
        final int tileSize = 64;

        Path inputDir = tempDir.resolve("s2-input");
        Files.createDirectories(inputDir);
        List<Path> inputs = new ArrayList<>(dimensions.length);
        int expectedTotalTiles = 0;

        for (int i = 0; i < dimensions.length; i++) {
            int w = dimensions[i][0];
            int h = dimensions[i][1];
            expectedTotalTiles += Math.ceilDiv(w, tileSize) * Math.ceilDiv(h, tileSize);
            Path file = inputDir.resolve(String.format(Locale.ROOT, "asym_%02d_%dx%d.png", i + 1, w, h));
            ImageBuffer img = DatasetGenerator.generateImage(i, w, h);
            img.writePng(file);
            inputs.add(file);
        }

        Path seqOut = tempDir.resolve("s2-seq");
        Path blocksOut = tempDir.resolve("s2-blocks");

        long seqStart = System.nanoTime();
        new SequentialEngine().process(inputs, seqOut, FilterType.SOBEL, null);
        long seqMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - seqStart);

        ThreadedBlocksEngine blocksEngine = new ThreadedBlocksEngine(threads, tileSize);
        long blocksStart = System.nanoTime();
        ImageEngine.EngineResult blocksRes = blocksEngine.process(inputs, blocksOut, FilterType.SOBEL, null);
        long blocksMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blocksStart);

        List<Path> seqFiles = listSortedPngs(seqOut);
        List<Path> blocksFiles = listSortedPngs(blocksOut);
        PixelVerifier.Result parity = PixelVerifier.verifyDirectories(seqFiles, blocksFiles);
        assertTrue(parity.exactMatch(),
                "ThreadedBlocksEngine parity mismatch: diffPixels=" + parity.diffPixels()
                        + " at (" + parity.firstX() + "," + parity.firstY() + ")");
        assertEquals(0L, parity.diffPixels());

        int[] tileCounts = blocksEngine.getLastRunTileCounts();
        assertEquals(threads, tileCounts.length, "Expected per-worker counts for " + threads + " threads");

        int totalObservedTiles = 0;
        int maxWorkerTiles = 0;
        int minWorkerTiles = Integer.MAX_VALUE;
        for (int count : tileCounts) {
            assertTrue(count > 0, "Worker processed 0 tiles in asymmetric batch: " + Arrays.toString(tileCounts));
            totalObservedTiles += count;
            maxWorkerTiles = Math.max(maxWorkerTiles, count);
            minWorkerTiles = Math.min(minWorkerTiles, count);
        }

        assertEquals(expectedTotalTiles, totalObservedTiles,
                "Total processed tiles mismatch: expected=" + expectedTotalTiles + ", actual=" + totalObservedTiles);

        double maxShare = maxWorkerTiles / (double) totalObservedTiles;
        assertTrue(maxShare < 0.65,
                String.format(Locale.ROOT,
                        "Dynamic tile distribution too skewed: maxShare=%.3f, counts=%s",
                        maxShare, Arrays.toString(tileCounts)));

        System.out.printf(Locale.ROOT,
                "[Scenario 2] seqMs=%d, blocksMs=%d (computeMs=%d), totalTiles=%d, workerCounts=%s, min=%d, max=%d (maxShare=%.2f%%)%n",
                seqMs, blocksMs, blocksRes.computeMs(), totalObservedTiles,
                Arrays.toString(tileCounts), minWorkerTiles, maxWorkerTiles, maxShare * 100.0);
    }

    @Test
    void benchmarkAllAndLargeReportOverTcpProtocol() throws Exception {
        Path inputDir = tempDir.resolve("s3a-input");
        List<Path> inputs = DatasetGenerator.generateDataset(inputDir, 3, 128, 96);

        Path expectedDir = tempDir.resolve("s3a-expected-seq");
        new SequentialEngine().process(inputs, expectedDir, FilterType.SHARPEN, null);
        List<Path> expectedFiles = listSortedPngs(expectedDir);

        long benchmarkStartNs = System.nanoTime();
        ValidOutcome benchmarkOutcome;
        try (CoordinatorHarness harness = new CoordinatorHarness(tempDir.resolve("s3a-work"), 2, 5_000L)) {
            JobRequest req = new JobRequest(FilterType.SHARPEN, ExecutionMode.BENCHMARK_ALL, 4, 64, 2);
            benchmarkOutcome = runValidClient(
                    harness.rmiPort,
                    req,
                    inputs,
                    tempDir.resolve("s3a-bench-out"),
                    ConcurrentHashMap.newKeySet());
        }
        long benchmarkMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - benchmarkStartNs);

        assertTrue(benchmarkOutcome.doneReceived(), "BENCHMARK_ALL job did not receive FRAME_DONE");
        assertNotNull(benchmarkOutcome.report(), "BENCHMARK_ALL job did not receive FRAME_REPORT");
        assertFalse(benchmarkOutcome.report().tableText().isBlank(), "BENCHMARK_ALL tableText must not be blank");
        assertFalse(benchmarkOutcome.report().csvText().isBlank(), "BENCHMARK_ALL csvText must not be blank");

        PixelVerifier.Result benchParity = PixelVerifier.verifyDirectories(expectedFiles, benchmarkOutcome.receivedFiles());
        assertTrue(benchParity.exactMatch(),
                "BENCHMARK_ALL output mismatch: diffPixels=" + benchParity.diffPixels());

        // Also stress TcpProtocol directly over loopback socket with >70 KB table and >70 KB CSV
        List<BenchmarkReport.Entry> largeEntries = new ArrayList<>(1_200);
        String longNote = "STRESS_NOTE_".repeat(16);
        for (int i = 0; i < 1_200; i++) {
            largeEntries.add(new BenchmarkReport.Entry(
                    "ENGINE_STRESS_" + i,
                    4,
                    100L + i,
                    120L + i,
                    2.5,
                    62.5,
                    0L,
                    true,
                    longNote + i));
        }
        BenchmarkReport largeReport = new BenchmarkReport(FilterType.SHARPEN, 10, 1280, 960, largeEntries);
        String expectedTable = largeReport.formatTable();
        String expectedCsv = largeReport.toCsv();
        int tableBytes = expectedTable.getBytes(StandardCharsets.UTF_8).length;
        int csvBytes = expectedCsv.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(tableBytes > LARGE_REPORT_MIN_BYTES,
                "Expected tableBytes > " + LARGE_REPORT_MIN_BYTES + ", got " + tableBytes);
        assertTrue(csvBytes > LARGE_REPORT_MIN_BYTES,
                "Expected csvBytes > " + LARGE_REPORT_MIN_BYTES + ", got " + csvBytes);

        long largeTcpStartNs = System.nanoTime();
        TcpProtocol.ReportFrame receivedLargeFrame;
        try (ServerSocket loopbackServer = new ServerSocket(0, 1, InetAddress.getByName(HOST));
             ExecutorService ioExec = Executors.newSingleThreadExecutor()) {
            Future<Void> serverFuture = ioExec.submit(() -> {
                try (Socket serverSide = loopbackServer.accept();
                     DataOutputStream out = new DataOutputStream(new BufferedOutputStream(serverSide.getOutputStream()))) {
                    TcpProtocol.sendReport(out, largeReport);
                }
                return null;
            });

            try (Socket clientSide = new Socket(HOST, loopbackServer.getLocalPort());
                 DataInputStream in = new DataInputStream(new BufferedInputStream(clientSide.getInputStream()))) {
                clientSide.setSoTimeout(10_000);
                byte frameType = in.readByte();
                assertEquals(TcpProtocol.FRAME_REPORT, frameType, "Expected FRAME_REPORT over TCP loopback");
                receivedLargeFrame = TcpProtocol.readReportPayload(in);
            }
            serverFuture.get(10, TimeUnit.SECONDS);
        }
        long largeTcpMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - largeTcpStartNs);

        assertEquals(expectedTable, receivedLargeFrame.tableText(), "Large tableText corrupted over TCP");
        assertEquals(expectedCsv, receivedLargeFrame.csvText(), "Large csvText corrupted over TCP");
        assertEquals(0L, receivedLargeFrame.diffPixels());
        assertTrue(receivedLargeFrame.verified());

        System.out.printf(Locale.ROOT,
                "[Scenario 3a] benchmarkAllMs=%d (tableBytes=%d, csvBytes=%d), largeLoopbackTcpMs=%d (largeTableBytes=%d, largeCsvBytes=%d)%n",
                benchmarkMs,
                benchmarkOutcome.report().tableText().getBytes(StandardCharsets.UTF_8).length,
                benchmarkOutcome.report().csvText().getBytes(StandardCharsets.UTF_8).length,
                largeTcpMs,
                tableBytes,
                csvBytes);
    }

    @Test
    void openClGpuEngineConsecutiveCallsStability() throws Exception {
        Assumptions.assumeTrue(OpenClGpuEngine.isOpenClAvailable(), "OpenCL GPU not available");

        final int consecutiveRuns = 10;
        Path inputDir = tempDir.resolve("s3b-input");
        List<Path> inputs = DatasetGenerator.generateDataset(inputDir, 4, 256, 192);

        Map<FilterType, List<Path>> expectedByFilter = new EnumMap<>(FilterType.class);
        SequentialEngine seqEngine = new SequentialEngine();
        for (FilterType filter : FilterType.values()) {
            Path seqOut = tempDir.resolve("s3b-seq-" + filter.name().toLowerCase(Locale.ROOT));
            seqEngine.process(inputs, seqOut, filter, null);
            expectedByFilter.put(filter, listSortedPngs(seqOut));
        }

        FilterType[] filters = FilterType.values();
        long[] runTimesMs = new long[consecutiveRuns];
        long totalStartNs = System.nanoTime();

        try (OpenClGpuEngine gpu = new OpenClGpuEngine()) {
            for (int i = 0; i < consecutiveRuns; i++) {
                FilterType filter = filters[i % filters.length];
                Path gpuOut = tempDir.resolve("s3b-gpu-run-" + i);
                long t0 = System.nanoTime();
                ImageEngine.EngineResult res = gpu.process(inputs, gpuOut, filter, null);
                runTimesMs[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

                List<Path> actualFiles = listSortedPngs(gpuOut);
                PixelVerifier.Result parity = PixelVerifier.verifyDirectories(expectedByFilter.get(filter), actualFiles);
                assertTrue(parity.exactMatch(),
                        "OpenCL parity mismatch on run " + i + " (" + filter + "): diffPixels=" + parity.diffPixels());
                assertEquals(0L, parity.diffPixels());
                assertTrue(res.workers() >= 1);
            }
        }

        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartNs);
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);

        System.out.printf(Locale.ROOT,
                "[Scenario 3b] runs=%d, totalMs=%d, perRunMs=%s, heapUsedMB=%d%n",
                consecutiveRuns, totalMs, Arrays.toString(runTimesMs), usedMb);
    }

    private record ValidOutcome(
            String jobId,
            TcpProtocol.ReportFrame report,
            List<Path> receivedFiles,
            boolean doneReceived) {
    }

    private ValidOutcome runValidClient(
            int rmiPort,
            JobRequest request,
            List<Path> inputs,
            Path outputDir,
            ConcurrentHashMap.KeySetView<String, Boolean> trackedJobIds) throws Exception {
        Files.createDirectories(outputDir);
        Registry reg = LocateRegistry.getRegistry(HOST, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) reg.lookup(CoordinatorRmiService.BINDING_NAME);

        JobTicket ticket = rmi.submitJob(request);
        trackedJobIds.add(ticket.getJobId());

        TcpProtocol.ReportFrame report = null;
        try (Socket socket = new Socket(ticket.getSocketHost(), ticket.getSocketPort());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            socket.setSoTimeout(60_000);
            TcpProtocol.sendUploadBatch(out, ticket.getJobId(), inputs);

            while (true) {
                byte frameType = in.readByte();
                switch (frameType) {
                    case TcpProtocol.FRAME_PROGRESS -> TcpProtocol.readProgressPayload(in);
                    case TcpProtocol.FRAME_REPORT -> report = TcpProtocol.readReportPayload(in);
                    case TcpProtocol.FRAME_IMAGE -> TcpProtocol.readImageToDir(in, outputDir);
                    case TcpProtocol.FRAME_DONE -> {
                        TcpProtocol.readDonePayload(in);
                        return new ValidOutcome(ticket.getJobId(), report, listSortedPngs(outputDir), true);
                    }
                    case TcpProtocol.FRAME_ERROR -> throw new IOException(
                            "Server returned error: " + TcpProtocol.readErrorPayload(in));
                    default -> throw new IOException("Unexpected TCP frame: " + frameType);
                }
            }
        }
    }

    private String runTruncatedClient(
            int rmiPort,
            JobRequest request,
            int index,
            ConcurrentHashMap.KeySetView<String, Boolean> trackedJobIds) throws Exception {
        Registry reg = LocateRegistry.getRegistry(HOST, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) reg.lookup(CoordinatorRmiService.BINDING_NAME);
        JobTicket ticket = rmi.submitJob(request);
        trackedJobIds.add(ticket.getJobId());

        try (Socket socket = new Socket(ticket.getSocketHost(), ticket.getSocketPort());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            out.writeUTF(ticket.getJobId());
            out.writeInt(1);
            out.writeUTF("truncated_" + index + ".png");
            out.writeLong(8_192L);
            out.write(new byte[32]);
            out.flush();
        }
        return ticket.getJobId();
    }

    private String submitOrphanRmiJob(
            int rmiPort,
            JobRequest request,
            ConcurrentHashMap.KeySetView<String, Boolean> trackedJobIds) throws Exception {
        Registry reg = LocateRegistry.getRegistry(HOST, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) reg.lookup(CoordinatorRmiService.BINDING_NAME);
        JobTicket ticket = rmi.submitJob(request);
        trackedJobIds.add(ticket.getJobId());
        return ticket.getJobId();
    }

    private static AtomicInteger getQueuedOrActive(JobCoordinator coord) throws Exception {
        Field field = JobCoordinator.class.getDeclaredField("queuedOrActive");
        field.setAccessible(true);
        return (AtomicInteger) field.get(coord);
    }

    private static Map<?, ?> getJobsMap(JobCoordinator coord) throws Exception {
        Field field = JobCoordinator.class.getDeclaredField("jobs");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(coord);
    }

    private static ThreadPoolExecutor getWorkerPool(JobCoordinator coord) throws Exception {
        Field field = JobCoordinator.class.getDeclaredField("workerPool");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(coord);
    }

    private static List<Path> listSortedPngs(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return ss.getLocalPort();
        }
    }

    private static final class CoordinatorHarness implements AutoCloseable {
        final int rmiPort;
        final int tcpPort;
        final JobCoordinator coordinator;
        final CoordinatorRmiImpl rmiImpl;
        final Registry registry;
        final ServerSocket serverSocket;
        final ExecutorService tcpAcceptPool;
        final Thread acceptThread;

        CoordinatorHarness(Path workDir, int maxConcurrent, long uploadTtlMs) throws Exception {
            System.setProperty("java.rmi.server.hostname", HOST);
            int rPort = findFreePort();
            int tPort = findFreePort();
            while (tPort == rPort) {
                tPort = findFreePort();
            }
            this.rmiPort = rPort;
            this.tcpPort = tPort;

            this.coordinator = new JobCoordinator(
                    HOST,
                    tcpPort,
                    maxConcurrent,
                    workDir,
                    "multicore",
                    null,
                    null,
                    null,
                    uploadTtlMs);
            this.rmiImpl = new CoordinatorRmiImpl(coordinator);
            this.registry = LocateRegistry.createRegistry(rmiPort);
            this.registry.rebind(CoordinatorRmiService.BINDING_NAME, rmiImpl);

            this.tcpAcceptPool = Executors.newCachedThreadPool();
            this.serverSocket = new ServerSocket(tcpPort, 64, InetAddress.getByName(HOST));
            this.acceptThread = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        tcpAcceptPool.submit(() -> coordinator.handleTcpClient(socket));
                    } catch (IOException e) {
                        if (serverSocket.isClosed()) {
                            break;
                        }
                    }
                }
            }, "stress-coordinator-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            acceptThread.interrupt();
            tcpAcceptPool.shutdownNow();
            coordinator.close();
            try {
                UnicastRemoteObject.unexportObject(rmiImpl, true);
            } catch (RemoteException ignored) {
            }
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (RemoteException ignored) {
            }
        }
    }
}
