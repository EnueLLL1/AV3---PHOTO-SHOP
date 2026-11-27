package estudio;

import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.model.JobStatus;
import estudio.model.JobTicket;
import estudio.net.JobCoordinator;
import estudio.net.TcpProtocol;
import estudio.core.DatasetGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobCoordinatorBugReproductionTest {

    private static final String HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    private int tcpPort;
    private JobCoordinator coordinator;
    private ServerSocket serverSocket;
    private ExecutorService tcpAcceptPool;
    private Thread acceptThread;

    @BeforeEach
    void setUp() throws Exception {
        tcpPort = findFreePort();
        coordinator = new JobCoordinator(
                HOST,
                tcpPort,
                1,
                tempDir.resolve("server-work"),
                "multicore",
                null,
                null,
                null,
                300L,
                300L);

        serverSocket = new ServerSocket(tcpPort, 16, InetAddress.getByName(HOST));
        tcpAcceptPool = Executors.newCachedThreadPool();
        acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    tcpAcceptPool.submit(() -> coordinator.handleTcpClient(client));
                } catch (IOException ignored) {
                    if (serverSocket.isClosed()) {
                        break;
                    }
                }
            }
        }, "test-tcp-accept-bug-repro");
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
    }

    @Test
    void handleTcpClientSendsProtocolErrorOnTruncatedStream() throws Exception {
        JobTicket ticket = coordinator.submitJob(new JobRequest(FilterType.BLUR, ExecutionMode.SEQUENTIAL, 1, 32, 1));

        try (Socket socket = new Socket(HOST, tcpPort)) {
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // Send valid jobId + 1 file header declaring 1024 bytes, then only 8 bytes and close output side -> EOFException on server.
            out.writeUTF(ticket.getJobId());
            out.writeInt(1);
            out.writeUTF("truncated.png");
            out.writeLong(1024L);
            out.write(new byte[8]);
            out.flush();
            socket.shutdownOutput();

            int frameType = in.read();
            assertNotEquals(-1, frameType,
                    "Expected FRAME_ERROR from coordinator on truncated upload stream, but socket closed without error frame");
            assertEquals(TcpProtocol.FRAME_ERROR, (byte) frameType);
            String errorMsg = TcpProtocol.readErrorPayload(in);
            assertFalse(errorMsg.isBlank(), "Error payload must not be blank");
        }
    }

    @Test
    void duplicateTcpConnectionDoesNotDecrementQueuedOrActive() throws Exception {
        Path inputDir = tempDir.resolve("dup-input");
        DatasetGenerator.generateDataset(inputDir, 6, 512, 512);
        List<Path> inputs = listSortedPngs(inputDir);

        JobTicket ticket1 = coordinator.submitJob(new JobRequest(FilterType.BLUR, ExecutionMode.SEQUENTIAL, 1, 32, 1));
        AtomicInteger queuedOrActive = getQueuedOrActive(coordinator);
        assertEquals(1, queuedOrActive.get());

        try (ExecutorService clientPool = Executors.newSingleThreadExecutor()) {
            Future<Boolean> firstClient = clientPool.submit(() -> runTcpClientToCompletion(ticket1.getJobId(), inputs));

            // Wait until job1 has transitioned out of AWAITING_UPLOAD (now QUEUED or RUNNING)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (coordinator.getJobStatus(ticket1.getJobId()).getState() == JobStatus.State.AWAITING_UPLOAD
                    && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }

            JobStatus.State stateBeforeDup = coordinator.getJobStatus(ticket1.getJobId()).getState();
            assertTrue(stateBeforeDup == JobStatus.State.QUEUED || stateBeforeDup == JobStatus.State.RUNNING,
                    "Expected job1 to be QUEUED or RUNNING before duplicate TCP connection, got: " + stateBeforeDup);

            // Send duplicate TCP connection for the same active jobId
            try (Socket dupSocket = new Socket(HOST, tcpPort)) {
                dupSocket.setSoTimeout(3_000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(dupSocket.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(dupSocket.getInputStream()));

                TcpProtocol.sendUploadBatch(out, ticket1.getJobId(), List.of());
                byte frameType = in.readByte();
                assertEquals(TcpProtocol.FRAME_ERROR, frameType,
                        "Duplicate TCP connection for active jobId must be rejected with FRAME_ERROR");
                TcpProtocol.readErrorPayload(in);
                // Wait for server-side handleTcpClient finally block to complete
                while (in.read() != -1) {
                    // drain until EOF
                }
            }
            Thread.sleep(50L);

            // While job1 is still active, queuedOrActive must NOT have been decremented to 0
            JobStatus.State stateAfterDup = coordinator.getJobStatus(ticket1.getJobId()).getState();
            assertTrue(stateAfterDup == JobStatus.State.QUEUED || stateAfterDup == JobStatus.State.RUNNING,
                    "Expected job1 to still be active after duplicate TCP rejection, got: " + stateAfterDup);
            assertTrue(queuedOrActive.get() >= 1,
                    "Duplicate TCP connection decremented queuedOrActive to " + queuedOrActive.get()
                            + " while job1 was still " + stateAfterDup);

            assertTrue(firstClient.get(30, TimeUnit.SECONDS), "Original job1 client should complete successfully");
            assertEquals(0, queuedOrActive.get(), "queuedOrActive should be 0 after job1 completes");
        }
    }

    @Test
    void abandonedRmiJobExpiresAfterTtlAndReleasesSlot() throws Exception {
        JobTicket ticket = coordinator.submitJob(new JobRequest(FilterType.BLUR, ExecutionMode.SEQUENTIAL, 1, 32, 1));
        AtomicInteger queuedOrActive = getQueuedOrActive(coordinator);
        Map<?, ?> jobs = getJobsMap(coordinator);

        assertEquals(1, queuedOrActive.get());
        assertTrue(jobs.containsKey(ticket.getJobId()));

        // Wait a short bounded window for TTL expiration of an abandoned AWAITING_UPLOAD job
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
        while ((queuedOrActive.get() > 0 || jobs.containsKey(ticket.getJobId()))
                && System.nanoTime() < deadline) {
            Thread.sleep(50L);
        }

        assertEquals(0, queuedOrActive.get(),
                "Abandoned RMI job without TCP upload must expire after TTL and release queuedOrActive");
        assertFalse(jobs.containsKey(ticket.getJobId()),
                "Abandoned RMI job without TCP upload must be removed from jobs map after TTL");
    }

    @Test
    void completedJobIsEvictedFromJobsMapAfterTtl() throws Exception {
        Path inputDir = tempDir.resolve("evict-input");
        DatasetGenerator.generateDataset(inputDir, 1, 16, 16);
        List<Path> inputs = listSortedPngs(inputDir);

        JobTicket ticket = coordinator.submitJob(new JobRequest(FilterType.BLUR, ExecutionMode.SEQUENTIAL, 1, 16, 1));
        assertTrue(runTcpClientToCompletion(ticket.getJobId(), inputs), "Job should complete successfully");

        Map<?, ?> jobs = getJobsMap(coordinator);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
        while (jobs.containsKey(ticket.getJobId()) && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }

        assertFalse(jobs.containsKey(ticket.getJobId()),
                "Completed job must be evicted from jobs map after completedTtlMs");
        Path jobDir = tempDir.resolve("server-work").resolve("jobs").resolve(ticket.getJobId());
        assertFalse(Files.exists(jobDir),
                "Completed job workspace directory must be cleaned up on eviction");
    }

    private boolean runTcpClientToCompletion(String jobId, List<Path> inputs) throws IOException {
        Path outDir = tempDir.resolve("client-out-" + jobId);
        Files.createDirectories(outDir);
        try (Socket socket = new Socket(HOST, tcpPort)) {
            socket.setSoTimeout(30_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            TcpProtocol.sendUploadBatch(out, jobId, inputs);
            while (true) {
                byte frameType = in.readByte();
                switch (frameType) {
                    case TcpProtocol.FRAME_PROGRESS -> TcpProtocol.readProgressPayload(in);
                    case TcpProtocol.FRAME_REPORT -> TcpProtocol.readReportPayload(in);
                    case TcpProtocol.FRAME_IMAGE -> TcpProtocol.readImageToDir(in, outDir);
                    case TcpProtocol.FRAME_DONE -> {
                        TcpProtocol.readDonePayload(in);
                        return true;
                    }
                    case TcpProtocol.FRAME_ERROR -> {
                        TcpProtocol.readErrorPayload(in);
                        return false;
                    }
                    default -> throw new IOException("Unexpected frame: " + frameType);
                }
            }
        }
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

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return socket.getLocalPort();
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
