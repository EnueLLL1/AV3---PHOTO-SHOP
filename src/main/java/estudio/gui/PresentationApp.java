package estudio.gui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public final class PresentationApp {

    private record AppConfig(
            boolean autoServer,
            String host,
            int rmiPort,
            int tcpPort,
            int httpPort,
            boolean explicitHttpPort,
            boolean noBrowser) {
    }

    private PresentationApp() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = parseArgs(args);
        Path workspaceBaseDir = Path.of("work");

        WebStudioServer server = startWithFallback(config, workspaceBaseDir);
        CountDownLatch shutdownLatch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } finally {
                shutdownLatch.countDown();
            }
        }, "web-studio-shutdown"));

        printBanner(server);

        if (!config.noBrowser()) {
            openBrowserBestEffort(server.getBaseUrl());
        }

        shutdownLatch.await();
    }

    private static WebStudioServer startWithFallback(AppConfig config, Path workspaceBaseDir) throws IOException {
        try {
            return WebStudioServer.start(
                    config.httpPort(),
                    config.host(),
                    config.rmiPort(),
                    config.tcpPort(),
                    2,
                    "multicore",
                    config.autoServer(),
                    workspaceBaseDir);
        } catch (BindException ex) {
            if (config.explicitHttpPort()) {
                throw ex;
            }
            System.out.printf(Locale.ROOT,
                    "[AV3 Web Studio] Porta %d ocupada; alocando porta HTTP livre automaticamente...%n",
                    config.httpPort());
            return WebStudioServer.start(
                    0,
                    config.host(),
                    config.rmiPort(),
                    config.tcpPort(),
                    2,
                    "multicore",
                    config.autoServer(),
                    workspaceBaseDir);
        }
    }

    private static void printBanner(WebStudioServer server) {
        String coordState = server.isCoordinatorRunning()
                ? "ATIVO"
                : "INATIVO (verifique logs ou inicie pela UI)";
        System.out.println("============================================================");
        System.out.println("  AV3 Photo Shop - Web Studio Local");
        System.out.println("  URL:         " + server.getBaseUrl());
        System.out.println("  Coordenador: " + coordState);
        System.out.println("  Pressione Ctrl+C para encerrar.");
        System.out.println("============================================================");
    }

    private static void openBrowserBestEffort(String url) {
        Thread.ofVirtual().name("open-browser").start(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                }
            } catch (Throwable ignored) {
            }

            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", url);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.start();
            } catch (Throwable ignored) {
            }
        });
    }

    static AppConfig parseArgs(String[] args) {
        boolean autoServer = true;
        String host = "127.0.0.1";
        int rmiPort = 1099;
        int tcpPort = 9090;
        int httpPort = 8080;
        boolean explicitHttpPort = false;
        boolean noBrowser = false;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--no-auto-server" -> autoServer = false;
                    case "--host" -> host = requireArgValue(args, ++i, "--host");
                    case "--rmi-port" -> rmiPort = parsePort(requireArgValue(args, ++i, "--rmi-port"), "--rmi-port");
                    case "--tcp-port" -> tcpPort = parsePort(requireArgValue(args, ++i, "--tcp-port"), "--tcp-port");
                    case "--http-port" -> {
                        httpPort = parsePort(requireArgValue(args, ++i, "--http-port"), "--http-port");
                        explicitHttpPort = true;
                    }
                    case "--no-browser" -> noBrowser = true;
                    default -> throw new IllegalArgumentException("Argumento desconhecido: " + arg);
                }
            }
        }
        return new AppConfig(autoServer, host, rmiPort, tcpPort, httpPort, explicitHttpPort, noBrowser);
    }

    private static int parsePort(String raw, String flag) {
        int port = Integer.parseInt(raw);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(flag + " fora da faixa [1..65535]: " + port);
        }
        return port;
    }

    private static String requireArgValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Valor ausente para " + flag);
        }
        return args[idx];
    }
}
