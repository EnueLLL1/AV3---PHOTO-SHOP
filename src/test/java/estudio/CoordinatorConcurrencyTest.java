package estudio;

import estudio.core.DatasetGenerator;
import estudio.core.PixelVerifier;
import estudio.engine.SequentialEngine;
import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.model.JobStatus;
import estudio.model.JobTicket;
import estudio.net.CoordinatorRmiImpl;
import estudio.net.CoordinatorRmiService;
import estudio.net.JobCoordinator;
import estudio.net.TcpProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorConcurrencyTest {

    private static final String HOST = "127.0.0.1";
    private static final int MAX_CONCURRENT = 2;

    @TempDir
    Path tempDir;

    private int rmiPort;
    private int tcpPort;
    private JobCoordinator coordinator;
    private CoordinatorRmiImpl rmiImpl;
    private Registry registry;
    private ServerSocket serverSocket;
    private ExecutorService tcpAcceptPool;
    private Thread acceptThread;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("java.rmi.server.hostname", HOST);
        rmiPort = findFreePort();
        tcpPort = findFreePort();
        while (tcpPort == rmiPort) {
            tcpPort = findFreePort();
        }

        coordinator = new JobCoordinator(
                HOST,
                tcpPort,
                MAX_CONCURRENT,
                tempDir.resolve("server-work"),
                "multicore",
                null,
                null,
                null);

        rmiImpl = new CoordinatorRmiImpl(coordinator);
        registry = LocateRegistry.createRegistry(rmiPort);
        registry.rebind(CoordinatorRmiService.BINDING_NAME, rmiImpl);

        tcpAcceptPool = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(tcpPort, 50, InetAddress.getByName(HOST));
        acceptThread = new Thread(() -> {
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
        }, "test-coordinator-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @AfterEach
    void tearDown() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (tcpAcceptPool != null) {
            tcpAcceptPool.shutdownNow();
        }
        if (coordinator != null) {
            coordinator.close();
        }
        if (rmiImpl != null) {
            try {
                UnicastRemoteObject.unexportObject(rmiImpl, true);
            } catch (RemoteException ignored) {
            }
        }
        if (registry != null) {
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (RemoteException ignored) {
            }
        }
    }

    @Test
    void testFourConcurrentClientsRespectMaxConcurrentAndReturnExactImages() throws Exception {
        Path inputDir = tempDir.resolve("input");
        List<Path> inputs = DatasetGenerator.generateDataset(inputDir, 2, 32, 32);

        Path expectedOutDir = tempDir.resolve("expected-seq-sobel");
        new SequentialEngine().process(inputs, expectedOutDir, FilterType.SOBEL, null);
        List<Path> expectedFiles = listSortedPngs(expectedOutDir);
        assertEquals(2, expectedFiles.size());

        List<JobRequest> requests = List.of(
                new JobRequest(FilterType.SOBEL, ExecutionMode.SEQUENTIAL, 1, 16, 2),
                new JobRequest(FilterType.SOBEL, ExecutionMode.THREADS_ROWS, 2, 16, 2),
                new JobRequest(FilterType.SOBEL, ExecutionMode.THREADS_BLOCKS, 2, 16, 2),
                new JobRequest(FilterType.SOBEL, ExecutionMode.BENCHMARK_ALL, 2, 16, 2));

        var jobIds = ConcurrentHashMap.<String>newKeySet();
        AtomicBoolean monitoring = new AtomicBoolean(true);
        AtomicInteger maxObservedRunning = new AtomicInteger(0);

        Registry clientRegistry = LocateRegistry.getRegistry(HOST, rmiPort);
        CoordinatorRmiService monitorRmi = (CoordinatorRmiService) clientRegistry.lookup(CoordinatorRmiService.BINDING_NAME);

        Thread monitorThread = new Thread(() -> {
            while (monitoring.get()) {
                int runningNow = 0;
                for (String id : jobIds) {
                    try {
                        if (monitorRmi.getJobStatus(id).getState() == JobStatus.State.RUNNING) {
                            runningNow++;
                        }
                    } catch (RemoteException ignored) {
                    }
                }
                int currentMax = maxObservedRunning.get();
                while (runningNow > currentMax && !maxObservedRunning.compareAndSet(currentMax, runningNow)) {
                    currentMax = maxObservedRunning.get();
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "test-concurrency-monitor");
        monitorThread.start();

        CountDownLatch readyGate = new CountDownLatch(requests.size());
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<ClientOutcome>> futures = new ArrayList<>(requests.size());

        try (ExecutorService clientPool = Executors.newFixedThreadPool(requests.size())) {
            for (int i = 0; i < requests.size(); i++) {
                final int idx = i;
                final JobRequest req = requests.get(i);
                final Path clientOut = tempDir.resolve("client-out-" + idx);
                futures.add(clientPool.submit(() -> {
                    readyGate.countDown();
                    startGate.await();
                    return executeClient(req, inputs, clientOut, jobIds);
                }));
            }

            assertTrue(readyGate.await(5, TimeUnit.SECONDS));
            startGate.countDown();

            List<ClientOutcome> outcomes = new ArrayList<>(requests.size());
            for (Future<ClientOutcome> f : futures) {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            }
            List<String> submittedJobIds = outcomes.stream().map(ClientOutcome::jobId).toList();

            monitoring.set(false);
            monitorThread.join(2000L);

            assertEquals(requests.size(), submittedJobIds.size());
            assertTrue(maxObservedRunning.get() <= MAX_CONCURRENT,
                    "Observed " + maxObservedRunning.get() + " concurrent RUNNING jobs (limit=" + MAX_CONCURRENT + ")");

            for (ClientOutcome outcome : outcomes) {
                JobStatus finalStatus = monitorRmi.getJobStatus(outcome.jobId());
                assertEquals(JobStatus.State.COMPLETED, finalStatus.getState(),
                        "Job " + outcome.jobId() + " did not complete: " + finalStatus);
                assertNotNull(outcome.report());
                assertEquals(inputs.size(), outcome.receivedFiles().size());

                PixelVerifier.Result verifyResult = PixelVerifier.verifyDirectories(expectedFiles, outcome.receivedFiles());
                assertTrue(verifyResult.exactMatch(),
                        "Socket PNG mismatch for job " + outcome.jobId() + ": diffPixels=" + verifyResult.diffPixels());
                assertEquals(0L, verifyResult.diffPixels());
            }
        } finally {
            monitoring.set(false);
            monitorThread.interrupt();
        }
    }

    private record ClientOutcome(String jobId, TcpProtocol.ReportFrame report, List<Path> receivedFiles) {
    }

    private ClientOutcome executeClient(
            JobRequest request,
            List<Path> inputs,
            Path outputDir,
            ConcurrentHashMap.KeySetView<String, Boolean> jobIds) throws Exception {
        Files.createDirectories(outputDir);
        Registry reg = LocateRegistry.getRegistry(HOST, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) reg.lookup(CoordinatorRmiService.BINDING_NAME);

        JobTicket ticket = rmi.submitJob(request);
        jobIds.add(ticket.getJobId());

        TcpProtocol.ReportFrame report = null;
        try (Socket socket = new Socket(ticket.getSocketHost(), ticket.getSocketPort());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

            TcpProtocol.sendUploadBatch(out, ticket.getJobId(), inputs);

            while (true) {
                byte frameType = in.readByte();
                switch (frameType) {
                    case TcpProtocol.FRAME_PROGRESS -> TcpProtocol.readProgressPayload(in);
                    case TcpProtocol.FRAME_REPORT -> report = TcpProtocol.readReportPayload(in);
                    case TcpProtocol.FRAME_IMAGE -> TcpProtocol.readImageToDir(in, outputDir);
                    case TcpProtocol.FRAME_DONE -> {
                        TcpProtocol.readDonePayload(in);
                        return new ClientOutcome(ticket.getJobId(), report, listSortedPngs(outputDir));
                    }
                    case TcpProtocol.FRAME_ERROR -> throw new IOException(
                            "Server returned error: " + TcpProtocol.readErrorPayload(in));
                    default -> throw new IOException("Unexpected TCP frame: " + frameType);
                }
            }
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return ss.getLocalPort();
        }
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
