package estudio.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CoordinatorMain {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_RMI_PORT = 1099;
    private static final int DEFAULT_TCP_PORT = 9090;
    private static final int DEFAULT_MAX_CONCURRENT = 2;
    private static final Path DEFAULT_WORK_DIR = Path.of("work", "server");
    private static final String DEFAULT_MPI_DEV = "multicore";

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

        System.setProperty("java.rmi.server.hostname", host);

        JobCoordinator coordinator = new JobCoordinator(
                host,
                tcpPort,
                maxConcurrent,
                workDir,
                mpiDev,
                mpiMachines,
                mpiHome,
                System.getProperty("java.class.path"));

        CoordinatorRmiImpl rmiImpl = new CoordinatorRmiImpl(coordinator);
        Registry registry = obtainOrCreateRegistry(rmiPort);
        registry.rebind(CoordinatorRmiService.BINDING_NAME, rmiImpl);

        ExecutorService tcpAcceptPool = Executors.newCachedThreadPool();
        ServerSocket serverSocket = new ServerSocket(tcpPort, 50, InetAddress.getByName(host));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            try {
                UnicastRemoteObject.unexportObject(rmiImpl, true);
            } catch (RemoteException ignored) {
            }
            tcpAcceptPool.shutdownNow();
            coordinator.close();
        }, "coordinator-shutdown"));

        System.out.printf("Coordinator started: host=%s rmiPort=%d tcpPort=%d maxConcurrent=%d workDir=%s mpiDev=%s%n",
                host, rmiPort, tcpPort, maxConcurrent, workDir.toAbsolutePath().normalize(), mpiDev);
        System.out.println("RMI binding: " + CoordinatorRmiService.BINDING_NAME);

        while (!serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                tcpAcceptPool.submit(() -> coordinator.handleTcpClient(clientSocket));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    break;
                }
                System.err.println("TCP accept error: " + e.getMessage());
            }
        }
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
        return args[idx];
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
