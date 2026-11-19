package estudio.net;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.CodeSource;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class CoordinatorMain {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_RMI_PORT = 1099;
    private static final int DEFAULT_TCP_PORT = 9090;
    private static final int DEFAULT_MAX_CONCURRENT = 2;
    private static final Path DEFAULT_WORK_DIR = Path.of("work", "server");
    private static final String DEFAULT_MPI_DEV = "multicore";

    public static final class EmbeddedServer implements AutoCloseable {
        private final String host;
        private final int rmiPort;
        private final int tcpPort;
        private final JobCoordinator coordinator;
        private final CoordinatorRmiImpl rmiImpl;
        private final Registry registry;
        private final ServerSocket serverSocket;
        private final ExecutorService tcpAcceptPool;
        private final CountDownLatch stopLatch;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private EmbeddedServer(
                String host,
                int rmiPort,
                int tcpPort,
                JobCoordinator coordinator,
                CoordinatorRmiImpl rmiImpl,
                Registry registry,
                ServerSocket serverSocket,
                ExecutorService tcpAcceptPool,
                CountDownLatch stopLatch) {
            this.host = host;
            this.rmiPort = rmiPort;
            this.tcpPort = tcpPort;
            this.coordinator = coordinator;
            this.rmiImpl = rmiImpl;
            this.registry = registry;
            this.serverSocket = serverSocket;
            this.tcpAcceptPool = tcpAcceptPool;
            this.stopLatch = stopLatch;
        }

        public String getHost() {
            return host;
        }

        public int getRmiPort() {
            return rmiPort;
        }

        public int getTcpPort() {
            return tcpPort;
        }

        public boolean isRunning() {
            return !closed.get() && !serverSocket.isClosed();
        }

        public void awaitStop() throws InterruptedException {
            stopLatch.await();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            try {
                registry.unbind(CoordinatorRmiService.BINDING_NAME);
            } catch (Exception ignored) {
            }
            try {
                UnicastRemoteObject.unexportObject(rmiImpl, true);
            } catch (NoSuchObjectException ignored) {
            }
            tcpAcceptPool.shutdownNow();
            coordinator.close();
            stopLatch.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        String host = DEFAULT_HOST;
        int rmiPort = DEFAULT_RMI_PORT;
        int tcpPort = DEFAULT_TCP_PORT;
        int maxConcurrent = DEFAULT_MAX_CONCURRENT;
        Path workDir = DEFAULT_WORK_DIR;
        String mpiDev = DEFAULT_MPI_DEV;
        String mpiMachines = null;
        String mpiHome = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--host" -> host = requireValue(args, ++i, "--host");
                case "--rmi-port" -> rmiPort = Integer.parseInt(requireValue(args, ++i, "--rmi-port"));
                case "--tcp-port" -> tcpPort = Integer.parseInt(requireValue(args, ++i, "--tcp-port"));
                case "--max-concurrent" -> maxConcurrent = Integer.parseInt(requireValue(args, ++i, "--max-concurrent"));
                case "--work-dir" -> workDir = Path.of(requireValue(args, ++i, "--work-dir"));
                case "--mpi-dev" -> mpiDev = requireValue(args, ++i, "--mpi-dev");
                case "--mpi-machines" -> mpiMachines = requireValue(args, ++i, "--mpi-machines");
                case "--mpi-home" -> mpiHome = requireValue(args, ++i, "--mpi-home");
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        EmbeddedServer server = startServer(
                host,
                rmiPort,
                tcpPort,
                maxConcurrent,
                workDir,
                mpiDev,
                mpiMachines == null ? null : Path.of(mpiMachines),
                mpiHome == null ? null : Path.of(mpiHome));

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "coordinator-shutdown"));

        System.out.printf("Coordinator started: host=%s rmiPort=%d tcpPort=%d maxConcurrent=%d workDir=%s mpiDev=%s%n",
                server.getHost(), server.getRmiPort(), server.getTcpPort(), maxConcurrent,
                workDir.toAbsolutePath().normalize(), mpiDev);
        System.out.println("RMI binding: " + CoordinatorRmiService.BINDING_NAME);

        server.awaitStop();
    }

    public static EmbeddedServer startServer(
            String host,
            int rmiPort,
            int tcpPort,
            int maxConcurrentJobs,
            Path workDir,
            String mpiDev,
            Path mpiMachines,
            Path mpiHome) throws Exception {
        String effectiveHost = (host == null || host.isBlank()) ? DEFAULT_HOST : host.trim();
        Path effectiveWorkDir = workDir == null ? DEFAULT_WORK_DIR : workDir;
        String effectiveMpiDev = (mpiDev == null || mpiDev.isBlank()) ? DEFAULT_MPI_DEV : mpiDev.trim();
        int effectiveMaxConcurrent = maxConcurrentJobs > 0 ? maxConcurrentJobs : DEFAULT_MAX_CONCURRENT;
        String mpiMachinesStr = mpiMachines == null ? null : mpiMachines.toString();
        String mpiHomeStr = mpiHome == null ? null : mpiHome.toString();

        System.setProperty("java.rmi.server.hostname", effectiveHost);

        JobCoordinator coordinator = new JobCoordinator(
                effectiveHost,
                tcpPort,
                effectiveMaxConcurrent,
                effectiveWorkDir,
                effectiveMpiDev,
                mpiMachinesStr,
                mpiHomeStr,
                resolveWorkerClasspath());

        CoordinatorRmiImpl rmiImpl = null;
        ServerSocket serverSocket = null;
        ExecutorService tcpAcceptPool = null;
        try {
            rmiImpl = new CoordinatorRmiImpl(coordinator);
            Registry registry = obtainOrCreateRegistry(rmiPort);
            registry.rebind(CoordinatorRmiService.BINDING_NAME, rmiImpl);

            serverSocket = new ServerSocket(tcpPort, 50, InetAddress.getByName(effectiveHost));
            tcpAcceptPool = Executors.newCachedThreadPool();
            CountDownLatch stopLatch = new CountDownLatch(1);

            final ServerSocket activeSocket = serverSocket;
            final ExecutorService activePool = tcpAcceptPool;
            Thread acceptThread = new Thread(() -> {
                try {
                    while (!activeSocket.isClosed()) {
                        try {
                            Socket clientSocket = activeSocket.accept();
                            activePool.submit(() -> coordinator.handleTcpClient(clientSocket));
                        } catch (IOException e) {
                            if (activeSocket.isClosed()) {
                                break;
                            }
                            System.err.println("TCP accept error: " + e.getMessage());
                        }
                    }
                } finally {
                    stopLatch.countDown();
                }
            }, "coordinator-tcp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            return new EmbeddedServer(
                    effectiveHost,
                    rmiPort,
                    serverSocket.getLocalPort(),
                    coordinator,
                    rmiImpl,
                    registry,
                    serverSocket,
                    tcpAcceptPool,
                    stopLatch);
        } catch (Exception e) {
            if (tcpAcceptPool != null) {
                tcpAcceptPool.shutdownNow();
            }
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
            if (rmiImpl != null) {
                try {
                    UnicastRemoteObject.unexportObject(rmiImpl, true);
                } catch (NoSuchObjectException ignored) {
                }
            }
            coordinator.close();
            throw e;
        }
    }

    private static String resolveWorkerClasspath() {
        String sysCp = System.getProperty("java.class.path");
        Path codeSourcePath = resolveCodeSourcePath();
        Path defaultOutMain = Path.of("out", "main").toAbsolutePath().normalize();

        Path expectedAppEntry = codeSourcePath != null ? codeSourcePath : defaultOutMain;
        if (classpathContainsPath(sysCp, expectedAppEntry)) {
            return sysCp;
        }

        // When launched via junit-platform-console-standalone.jar, java.class.path omits app classes;
        // returning null lets MpjSubprocessEngine assemble the full default classpath (including out/main and lib/*.jar).
        if (Files.isDirectory(defaultOutMain)
                && (codeSourcePath == null || codeSourcePath.equals(defaultOutMain))) {
            return null;
        }

        if (codeSourcePath != null) {
            String appEntry = codeSourcePath.toString();
            return (sysCp == null || sysCp.isBlank()) ? appEntry : appEntry + File.pathSeparator + sysCp;
        }
        return sysCp;
    }

    private static Path resolveCodeSourcePath() {
        try {
            CodeSource cs = CoordinatorMain.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                return Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean classpathContainsPath(String classpath, Path target) {
        if (classpath == null || classpath.isBlank() || target == null) {
            return false;
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        for (String part : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (part.isBlank()) {
                continue;
            }
            try {
                if (Path.of(part).toAbsolutePath().normalize().equals(normalizedTarget)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static Registry obtainOrCreateRegistry(int rmiPort) throws RemoteException {
        try {
            return LocateRegistry.createRegistry(rmiPort);
        } catch (RemoteException createEx) {
            Registry existing = LocateRegistry.getRegistry(rmiPort);
            existing.list();
            return existing;
        }
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return Objects.requireNonNull(args[idx]);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java ... estudio.net.CoordinatorMain [options]
                  --host <hostname>          RMI/TCP host advertised to clients (default: 127.0.0.1)
                  --rmi-port <port>          RMI registry port (default: 1099)
                  --tcp-port <port>          TCP socket port for batch upload/results (default: 9090)
                  --max-concurrent <n>       Max simultaneous jobs in workerPool (default: 2)
                  --work-dir <path>          Server workspace root (default: work/server)
                  --mpi-dev <dev>            MPJ Express device mode: multicore | niodev (default: multicore)
                  --mpi-machines <file>      Optional MPJ machines file
                  --mpi-home <dir>           Optional MPJ_HOME directory
                  --help, -h                 Show this help
                """);
    }
}
