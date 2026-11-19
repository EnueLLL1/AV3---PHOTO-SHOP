package estudio.gui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import estudio.core.DatasetGenerator;
import estudio.engine.OpenClGpuEngine;
import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.net.CoordinatorMain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class WebStudioServer implements AutoCloseable {
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final long MAX_JSON_BYTES = 1L * 1024L * 1024L;
    private static final long MAX_UPLOAD_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_LOG_ENTRIES = 300;
    private static final int STREAM_BUFFER_SIZE = 65_536;
    private static final Pattern SAFE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Set<String> VALID_SLOTS = Set.of("input", "output", "diff", "reports");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private record ServerConfig(String host, int rmiPort, int tcpPort, int maxConcurrent, String mpiDev) {
    }

    private record PingSnapshot(boolean ok, String response, long latencyMs, String checkedAt, String error) {
    }

    private record LogEntry(long id, String time, String level, String source, String message) {
    }

    private static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private final HttpServer httpServer;
    private final ExecutorService httpExecutor;
    private final Path workspaceBaseDir;
    private final Path sessionsDir;
    private final boolean openClAvailable;
    private final int cpuCores;

    private final Object coordinatorLock = new Object();
    private final Object logLock = new Object();
    private final Deque<LogEntry> logBuffer = new ArrayDeque<>(MAX_LOG_ENTRIES);
    private final AtomicLong logSeq = new AtomicLong(0L);
    private final AtomicLong sessionSeq = new AtomicLong(0L);
    private final AtomicLong runSeq = new AtomicLong(0L);
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ServerConfig config;
    private volatile CoordinatorMain.EmbeddedServer embeddedServer;
    private volatile PingSnapshot lastPing;

    private WebStudioServer(
            HttpServer httpServer,
            ExecutorService httpExecutor,
            Path workspaceBaseDir,
            ServerConfig initialConfig) {
        this.httpServer = httpServer;
        this.httpExecutor = httpExecutor;
        this.workspaceBaseDir = workspaceBaseDir.toAbsolutePath().normalize();
        this.sessionsDir = this.workspaceBaseDir.resolve("web-sessions");
        this.config = initialConfig;
        this.cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.openClAvailable = OpenClGpuEngine.isOpenClAvailable();
    }

    public static WebStudioServer start(
            int httpPort,
            String initialHost,
            int initialRmiPort,
            int initialTcpPort,
            int initialMaxConcurrent,
            String initialMpiDev,
            boolean autoStartCoordinator,
            Path workspaceBaseDir) throws IOException {
        Objects.requireNonNull(workspaceBaseDir, "workspaceBaseDir must not be null");
        String host = (initialHost == null || initialHost.isBlank()) ? LOOPBACK_HOST : initialHost.trim();
        int rmiPort = validatePort(initialRmiPort, "rmiPort", 1099);
        int tcpPort = validatePort(initialTcpPort, "tcpPort", 9090);
        int maxConcurrent = clampOrValidate(initialMaxConcurrent <= 0 ? 2 : initialMaxConcurrent, 1, 16, "maxConcurrent");
        String mpiDev = normalizeMpiDev(initialMpiDev == null || initialMpiDev.isBlank() ? "multicore" : initialMpiDev);

        Path normalizedBase = workspaceBaseDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedBase.resolve("web-sessions"));

        InetSocketAddress bindAddr = new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), httpPort);
        HttpServer server = HttpServer.create(bindAddr, 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        WebStudioServer instance = new WebStudioServer(
                server,
                executor,
                normalizedBase,
                new ServerConfig(host, rmiPort, tcpPort, maxConcurrent, mpiDev));
        instance.registerRoutes();
        server.start();
        instance.addLog("INFO", "WebStudio", "Servidor HTTP iniciado em " + instance.getBaseUrl());

        if (autoStartCoordinator) {
            instance.tryAutoStartCoordinator();
        } else {
            instance.addLog("INFO", "Coordinator", "Modo --no-auto-server ativo.");
        }

        return instance;
    }

    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    public String getBaseUrl() {
        return "http://" + LOOPBACK_HOST + ":" + getPort();
    }

    public boolean isRunning() {
        return !closed.get();
    }

    public boolean isCoordinatorRunning() {
        CoordinatorMain.EmbeddedServer srv = embeddedServer;
        return srv != null && srv.isRunning();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            httpServer.stop(0);
        } catch (Exception ignored) {
        }
        synchronized (coordinatorLock) {
            if (embeddedServer != null) {
                try {
                    embeddedServer.close();
                } catch (Exception ignored) {
                }
                embeddedServer = null;
            }
        }
        httpExecutor.shutdownNow();
    }

    private void registerRoutes() {
        httpServer.createContext("/", this::handleRootOrStatic);
        httpServer.createContext("/api/status", ex -> handleApi(ex, "GET", this::handleStatus));
        httpServer.createContext("/api/config", ex -> handleApi(ex, "POST", this::handleConfig));
        httpServer.createContext("/api/server/start", ex -> handleApi(ex, "POST", this::handleServerStart));
        httpServer.createContext("/api/server/stop", ex -> handleApi(ex, "POST", this::handleServerStop));
        httpServer.createContext("/api/ping", ex -> handleApi(ex, "POST", this::handlePing));
        httpServer.createContext("/api/logs", ex -> handleApi(ex, "GET", this::handleLogsGet));
        httpServer.createContext("/api/logs/clear", ex -> handleApi(ex, "POST", this::handleLogsClear));
        httpServer.createContext("/api/studio/generate", ex -> handleApi(ex, "POST", this::handleStudioGenerate));
        httpServer.createContext("/api/studio/upload", ex -> handleApi(ex, "POST", this::handleStudioUpload));
        httpServer.createContext("/api/studio/process", ex -> handleApi(ex, "POST", this::handleStudioProcess));
        httpServer.createContext("/api/benchmark/run", ex -> handleApi(ex, "POST", this::handleBenchmarkRun));
        httpServer.createContext("/api/concurrency/run", ex -> handleApi(ex, "POST", this::handleConcurrencyRun));
        httpServer.createContext("/api/files/", this::handleFilesGet);
    }

    @FunctionalInterface
    private interface ApiHandler {
        Object handle(HttpExchange exchange) throws Exception;
    }

    private void handleApi(HttpExchange exchange, String expectedMethod, ApiHandler handler) throws IOException {
        try {
            if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorMap("Metodo nao permitido: " + exchange.getRequestMethod()));
                return;
            }
            Object payload = handler.handle(exchange);
            sendJson(exchange, 200, payload);
        } catch (ApiException ex) {
            sendJson(exchange, ex.statusCode, errorMap(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, errorMap(ex.getMessage()));
        } catch (Exception ex) {
            String msg = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            addLog("ERROR", "API", exchange.getRequestURI().getPath() + ": " + msg);
            sendJson(exchange, 500, errorMap(msg));
        } finally {
            exchange.close();
        }
    }

    private void handleRootOrStatic(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorMap("Metodo nao permitido"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                sendJson(exchange, 404, errorMap("Endpoint nao encontrado: " + path));
                return;
            }
            String resourcePath;
            String contentType;
            switch (path) {
                case "/", "/index.html" -> {
                    resourcePath = "/web/index.html";
                    contentType = "text/html; charset=utf-8";
                }
                case "/styles.css" -> {
                    resourcePath = "/web/styles.css";
                    contentType = "text/css; charset=utf-8";
                }
                case "/app.js" -> {
                    resourcePath = "/web/app.js";
                    contentType = "application/javascript; charset=utf-8";
                }
                default -> {
                    byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                    Headers h = exchange.getResponseHeaders();
                    h.set("Content-Type", "text/plain; charset=utf-8");
                    h.set("X-Content-Type-Options", "nosniff");
                    h.set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(404, notFound.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(notFound);
                    }
                    return;
                }
            }

            byte[] content;
            try (InputStream is = WebStudioServer.class.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    content = is.readAllBytes();
                } else if ("/web/index.html".equals(resourcePath)) {
                    content = """
                            <!doctype html>
                            <html lang="pt-BR">
                            <head><meta charset="utf-8"><title>AV3 Web Studio</title></head>
                            <body><h1>AV3 Web Studio</h1><p>Backend API ativo.</p></body>
                            </html>
                            """.getBytes(StandardCharsets.UTF_8);
                } else {
                    byte[] notFound = ("Recurso nao encontrado: " + resourcePath).getBytes(StandardCharsets.UTF_8);
                    Headers h = exchange.getResponseHeaders();
                    h.set("Content-Type", "text/plain; charset=utf-8");
                    h.set("X-Content-Type-Options", "nosniff");
                    h.set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(404, notFound.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(notFound);
                    }
                    return;
                }
            }

            Headers h = exchange.getResponseHeaders();
            h.set("Content-Type", contentType);
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }
    }

    // =========================================================================
    // STATUS, CONFIG, COORDINATOR, PING, LOGS
    // =========================================================================

    private Map<String, Object> handleStatus(HttpExchange exchange) {
        return buildStatusMap();
    }

    private Map<String, Object> handleConfig(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        ServerConfig updated = parseAndApplyConfig(body);
        addLog("INFO", "Config", String.format(Locale.ROOT,
                "Config atualizada: host=%s rmi=%d tcp=%d maxConc=%d mpi=%s",
                updated.host(), updated.rmiPort(), updated.tcpPort(), updated.maxConcurrent(), updated.mpiDev()));
        return buildStatusMap();
    }

    private Map<String, Object> handleServerStart(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        ServerConfig effective = parseAndApplyConfig(body);
        startOrRestartCoordinator(effective);
        performPing(effective.host(), effective.rmiPort());
        return buildStatusMap();
    }

    private Map<String, Object> handleServerStop(HttpExchange exchange) {
        stopCoordinatorInternal();
        return buildStatusMap();
    }

    private Map<String, Object> handlePing(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        ServerConfig current = config;
        String host = getString(body, "host", current.host()).trim();
        if (host.isEmpty()) {
            throw new ApiException(400, "host nao pode ser vazio.");
        }
        int rmiPort = getInt(body, "rmiPort", current.rmiPort());
        validatePortRange(rmiPort, "rmiPort");

        PingSnapshot snap = performPing(host, rmiPort);
        return pingToMap(snap);
    }

    private Map<String, Object> handleLogsGet(HttpExchange exchange) throws ApiException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        long afterId = 0L;
        String afterRaw = query.get("after");
        if (afterRaw != null && !afterRaw.isBlank()) {
            try {
                afterId = Long.parseLong(afterRaw.trim());
            } catch (NumberFormatException ex) {
                throw new ApiException(400, "Parametro 'after' invalido.");
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        synchronized (logLock) {
            for (LogEntry entry : logBuffer) {
                if (entry.id() > afterId) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", entry.id());
                    row.put("time", entry.time());
                    row.put("level", entry.level());
                    row.put("source", entry.source());
                    row.put("message", entry.message());
                    items.add(row);
                }
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("logs", items);
        return res;
    }

    private Map<String, Object> handleLogsClear(HttpExchange exchange) {
        synchronized (logLock) {
            logBuffer.clear();
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        return res;
    }

    // =========================================================================
    // STUDIO: GENERATE, UPLOAD, PROCESS
    // =========================================================================

    private Map<String, Object> handleStudioGenerate(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        int width = getInt(body, "width", 384);
        int height = getInt(body, "height", 384);
        validateRange(width, 32, 2048, "width");
        validateRange(height, 32, 2048, "height");

        String sessionId = allocateSessionId("studio");
        Path inputDir = sessionsDir.resolve(sessionId).resolve("input");
        List<Path> files = DatasetGenerator.generateDataset(inputDir, 1, width, height);
        Path generated = files.get(0);
        String fileName = generated.getFileName().toString();

        addLog("INFO", "Studio", String.format(Locale.ROOT,
                "Imagem sintetica gerada (%dx%d) na sessao %s", width, height, sessionId));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("sessionId", sessionId);
        res.put("fileName", fileName);
        res.put("width", width);
        res.put("height", height);
        res.put("inputUrl", buildFileUrl(sessionId, "input", fileName));
        return res;
    }

    private Map<String, Object> handleStudioUpload(HttpExchange exchange) throws Exception {
        Headers reqHeaders = exchange.getRequestHeaders();
        String contentLenStr = reqHeaders.getFirst("Content-Length");
        if (contentLenStr != null && !contentLenStr.isBlank()) {
            try {
                long declaredLen = Long.parseLong(contentLenStr.trim());
                if (declaredLen > MAX_UPLOAD_BYTES) {
                    throw new ApiException(413, "Arquivo excede o limite de 32 MB.");
                }
            } catch (NumberFormatException ignored) {
            }
        }

        String rawName = reqHeaders.getFirst("X-Filename");
        if (rawName == null || rawName.isBlank()) {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            rawName = query.get("filename");
        }
        String safeFileName = sanitizeUploadFilename(rawName);

        String sessionId = allocateSessionId("studio");
        Path sessionDir = sessionsDir.resolve(sessionId);
        Path rawDir = sessionDir.resolve("raw");
        Path inputDir = sessionDir.resolve("input");
        Files.createDirectories(rawDir);
        Files.createDirectories(inputDir);

        Path rawFile = rawDir.resolve(safeFileName);
        long totalBytes = streamUploadToFile(exchange.getRequestBody(), rawFile);
        if (totalBytes == 0L) {
            Files.deleteIfExists(rawFile);
            throw new ApiException(400, "Corpo do upload esta vazio.");
        }

        List<Path> prepared;
        try {
            prepared = GuiClientService.ensurePngInputs(List.of(rawFile), inputDir);
        } catch (IOException ex) {
            throw new ApiException(400, "Imagem invalida ou formato nao suportado: " + ex.getMessage());
        }
        if (prepared.isEmpty()) {
            throw new ApiException(400, "Falha ao preparar imagem enviada.");
        }

        Path finalInput = prepared.get(0).toAbsolutePath().normalize();
        Path normalizedInputDir = inputDir.toAbsolutePath().normalize();
        if (!normalizedInputDir.equals(finalInput.getParent())) {
            Path target = normalizedInputDir.resolve(finalInput.getFileName().toString());
            Files.copy(finalInput, target, StandardCopyOption.REPLACE_EXISTING);
            finalInput = target;
        }

        BufferedImage decoded = ImageIO.read(finalInput.toFile());
        if (decoded == null) {
            Files.deleteIfExists(finalInput);
            throw new ApiException(400, "Arquivo enviado nao e uma imagem PNG/JPG valida.");
        }

        String finalFileName = finalInput.getFileName().toString();
        addLog("INFO", "Studio", String.format(Locale.ROOT,
                "Upload recebido: %s (%dx%d, %d bytes) -> sessao %s",
                finalFileName, decoded.getWidth(), decoded.getHeight(), totalBytes, sessionId));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("sessionId", sessionId);
        res.put("fileName", finalFileName);
        res.put("width", decoded.getWidth());
        res.put("height", decoded.getHeight());
        res.put("inputUrl", buildFileUrl(sessionId, "input", finalFileName));
        return res;
    }

    private Map<String, Object> handleStudioProcess(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        String sessionId = getRequiredString(body, "sessionId");
        validateSafeToken(sessionId, "sessionId");

        Path sessionDir = resolveSessionDir(sessionId);
        Path inputDir = sessionDir.resolve("input");
        if (!Files.isDirectory(inputDir)) {
            throw new ApiException(404, "Sessao nao encontrada: " + sessionId);
        }
        Path inputFile = findFirstPngInDir(inputDir);
        if (inputFile == null) {
            throw new ApiException(404, "Nenhuma imagem de entrada encontrada na sessao: " + sessionId);
        }

        FilterType filter = FilterType.fromString(getString(body, "filter", "SOBEL"));
        ExecutionMode mode = ExecutionMode.fromString(getString(body, "mode", "THREADS_BLOCKS"));
        if (mode == ExecutionMode.BENCHMARK_ALL) {
            throw new ApiException(400, "Use /api/benchmark/run para ExecutionMode.BENCHMARK_ALL.");
        }

        int threads = getInt(body, "threads", cpuCores);
        validateRange(threads, 1, 64, "threads");
        int tileSize = getInt(body, "tileSize", 64);
        validateRange(tileSize, 8, 2048, "tileSize");
        int defaultMpi = Math.max(1, Math.min(4, cpuCores));
        int mpiProcesses = getInt(body, "mpiProcesses", defaultMpi);
        validateRange(mpiProcesses, 1, 16, "mpiProcesses");
        boolean computeDiff = getBoolean(body, "computeDiff", true);

        ServerConfig cfg = config;
        long runId = runSeq.incrementAndGet();
        Path runWorkDir = sessionDir.resolve("runs").resolve("run_" + runId);
        Path outputSlotDir = sessionDir.resolve("output");
        Files.createDirectories(runWorkDir);
        Files.createDirectories(outputSlotDir);

        JobRequest req = new JobRequest(filter, mode, threads, tileSize, mpiProcesses);
        GuiClientService.JobResult result = GuiClientService.runJob(
                cfg.host(),
                cfg.rmiPort(),
                req,
                List.of(inputFile),
                runWorkDir,
                msg -> addLog("INFO", "Studio", msg),
                null);

        if (result.outputFiles().isEmpty()) {
            throw new ApiException(500, "Coordenador nao retornou imagem de saida.");
        }

        String outFileName = "out_" + runId + ".png";
        Path finalOutFile = outputSlotDir.resolve(outFileName);
        Files.copy(result.outputFiles().get(0), finalOutFile, StandardCopyOption.REPLACE_EXISTING);

        BufferedImage outImg = ImageIO.read(finalOutFile.toFile());
        if (outImg == null) {
            throw new ApiException(500, "Falha ao ler imagem de saida gerada.");
        }
        int width = outImg.getWidth();
        int height = outImg.getHeight();

        long computeMs = result.elapsedMs();
        long totalMs = result.elapsedMs();
        String notes = "";
        if (!result.parsedRows().isEmpty()) {
            GuiClientService.BenchmarkRow row = result.parsedRows().get(0);
            computeMs = row.computeMs();
            totalMs = row.totalMs();
            notes = row.notes() != null ? row.notes() : "";
        }

        long diffPixels = 0L;
        long totalPixels = (long) width * height;
        boolean verified = result.report() == null || result.report().verified();
        String diffUrl = null;

        if (computeDiff) {
            Path baselineDir = sessionDir.resolve("baseline");
            Path diffDir = sessionDir.resolve("diff");
            Files.createDirectories(baselineDir);
            Files.createDirectories(diffDir);
            Path baselineFile = baselineDir.resolve("baseline_" + filter.name() + ".png");
            String diffFileName = "diff_" + runId + ".png";
            Path diffFile = diffDir.resolve(diffFileName);

            Object sessionLock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
            if (mode == ExecutionMode.SEQUENTIAL) {
                synchronized (sessionLock) {
                    Files.copy(finalOutFile, baselineFile, StandardCopyOption.REPLACE_EXISTING);
                }
                GuiClientService.DiffComparison diff = GuiClientService.buildDiffHeatmap(outImg, outImg);
                ImageIO.write(diff.heatmapImage(), "png", diffFile.toFile());
                diffPixels = 0L;
                totalPixels = diff.totalPixels();
                verified = true;
            } else {
                BufferedImage baseImg;
                synchronized (sessionLock) {
                    if (!Files.isRegularFile(baselineFile)) {
                        Path seqWorkDir = sessionDir.resolve("runs").resolve("seq_baseline_" + runId);
                        Files.createDirectories(seqWorkDir);
                        JobRequest seqReq = new JobRequest(filter, ExecutionMode.SEQUENTIAL, 1, tileSize, 1);
                        GuiClientService.JobResult seqRes = GuiClientService.runJob(
                                cfg.host(),
                                cfg.rmiPort(),
                                seqReq,
                                List.of(inputFile),
                                seqWorkDir,
                                msg -> addLog("INFO", "Studio-Baseline", msg),
                                null);
                        if (seqRes.outputFiles().isEmpty()) {
                            throw new ApiException(500, "Falha ao gerar baseline SEQUENTIAL.");
                        }
                        Files.copy(seqRes.outputFiles().get(0), baselineFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    baseImg = ImageIO.read(baselineFile.toFile());
                }
                if (baseImg == null) {
                    throw new ApiException(500, "Falha ao ler baseline SEQUENTIAL.");
                }
                GuiClientService.DiffComparison diff = GuiClientService.buildDiffHeatmap(baseImg, outImg);
                ImageIO.write(diff.heatmapImage(), "png", diffFile.toFile());
                diffPixels = diff.diffPixels();
                totalPixels = diff.totalPixels();
                verified = (diffPixels == 0L);
            }
            diffUrl = buildFileUrl(sessionId, "diff", diffFileName);
        }

        String inputFileName = inputFile.getFileName().toString();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("sessionId", sessionId);
        res.put("filter", filter.name());
        res.put("mode", mode.name());
        res.put("jobId", result.ticket().getJobId());
        res.put("elapsedMs", result.elapsedMs());
        res.put("computeMs", computeMs);
        res.put("totalMs", totalMs);
        res.put("diffPixels", diffPixels);
        res.put("totalPixels", totalPixels);
        res.put("verified", verified);
        res.put("notes", notes);
        res.put("inputUrl", buildFileUrl(sessionId, "input", inputFileName));
        res.put("outputUrl", buildFileUrl(sessionId, "output", outFileName));
        res.put("diffUrl", diffUrl);
        res.put("width", width);
        res.put("height", height);
        return res;
    }

    // =========================================================================
    // BENCHMARK & CONCURRENCY
    // =========================================================================

    private Map<String, Object> handleBenchmarkRun(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        int count = getInt(body, "count", 4);
        int width = getInt(body, "width", 256);
        int height = getInt(body, "height", 256);
        validateRange(count, 1, 50, "count");
        validateRange(width, 16, 6000, "width");
        validateRange(height, 16, 6000, "height");

        FilterType filter = FilterType.fromString(getString(body, "filter", "SOBEL"));
        int threads = getInt(body, "threads", cpuCores);
        validateRange(threads, 1, 64, "threads");
        int tileSize = getInt(body, "tileSize", 64);
        validateRange(tileSize, 8, 2048, "tileSize");
        int defaultMpi = Math.max(1, Math.min(4, cpuCores));
        int mpiProcesses = getInt(body, "mpiProcesses", defaultMpi);
        validateRange(mpiProcesses, 1, 16, "mpiProcesses");

        String sessionId = allocateSessionId("bench");
        Path sessionDir = sessionsDir.resolve(sessionId);
        Path inputDir = sessionDir.resolve("input");
        Path reportsDir = sessionDir.resolve("reports");
        Files.createDirectories(reportsDir);

        addLog("INFO", "Benchmark", String.format(Locale.ROOT,
                "Gerando dataset %dx (%dx%d) para BENCHMARK_ALL (%s)...",
                count, width, height, filter.name()));
        List<Path> inputFiles = DatasetGenerator.generateDataset(inputDir, count, width, height);

        ServerConfig cfg = config;
        JobRequest req = new JobRequest(filter, ExecutionMode.BENCHMARK_ALL, threads, tileSize, mpiProcesses);
        GuiClientService.JobResult result = GuiClientService.runJob(
                cfg.host(),
                cfg.rmiPort(),
                req,
                inputFiles,
                reportsDir,
                msg -> addLog("INFO", "Benchmark", msg),
                null);

        List<Map<String, Object>> rowsJson = new ArrayList<>();
        for (GuiClientService.BenchmarkRow row : result.parsedRows()) {
            rowsJson.add(benchmarkRowToMap(row));
        }

        GuiClientService.BenchmarkRow winnerRow = selectWinnerRow(result.parsedRows());
        Map<String, Object> winnerJson = winnerRow != null ? benchmarkRowToMap(winnerRow) : null;

        String csvText = result.report() != null && result.report().csvText() != null
                ? result.report().csvText()
                : "";
        String txtText = result.report() != null && result.report().tableText() != null
                ? result.report().tableText()
                : "";

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("sessionId", sessionId);
        res.put("jobId", result.ticket().getJobId());
        res.put("elapsedMs", result.elapsedMs());
        res.put("filter", filter.name());
        res.put("count", count);
        res.put("width", width);
        res.put("height", height);
        res.put("winner", winnerJson);
        res.put("rows", rowsJson);
        res.put("csvUrl", buildFileUrl(sessionId, "reports", "benchmark_report.csv"));
        res.put("txtUrl", buildFileUrl(sessionId, "reports", "benchmark_report.txt"));
        res.put("csvText", csvText);
        res.put("txtText", txtText);
        return res;
    }

    private Map<String, Object> handleConcurrencyRun(HttpExchange exchange) throws Exception {
        Map<String, Object> body = readJsonObjectBody(exchange);
        int clients = getInt(body, "clients", 4);
        int count = getInt(body, "count", 2);
        int width = getInt(body, "width", 128);
        int height = getInt(body, "height", 128);
        validateRange(clients, 1, 8, "clients");
        validateRange(count, 1, 10, "count");
        validateRange(width, 16, 2048, "width");
        validateRange(height, 16, 2048, "height");

        FilterType filter = FilterType.fromString(getString(body, "filter", "BLUR"));
        ExecutionMode mode = ExecutionMode.fromString(getString(body, "mode", "THREADS_BLOCKS"));
        if (mode == ExecutionMode.BENCHMARK_ALL) {
            throw new ApiException(400, "Use um motor unico no simulador de concorrencia.");
        }
        int threads = getInt(body, "threads", cpuCores);
        validateRange(threads, 1, 64, "threads");
        int tileSize = getInt(body, "tileSize", 64);
        validateRange(tileSize, 8, 2048, "tileSize");
        int defaultMpi = Math.max(1, Math.min(4, cpuCores));
        int mpiProcesses = getInt(body, "mpiProcesses", defaultMpi);
        validateRange(mpiProcesses, 1, 16, "mpiProcesses");

        String sessionId = allocateSessionId("conc");
        Path sessionDir = sessionsDir.resolve(sessionId);
        Path inputDir = sessionDir.resolve("input");
        List<Path> inputFiles = DatasetGenerator.generateDataset(inputDir, count, width, height);

        ServerConfig cfg = config;
        JobRequest req = new JobRequest(filter, mode, threads, tileSize, mpiProcesses);
        addLog("INFO", "Concorrencia", String.format(Locale.ROOT,
                "Disparando %d clientes simultaneos (%s / %s, %dx %dx%d)...",
                clients, mode.name(), filter.name(), count, width, height));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(clients);
        List<Map<String, Object>> items = java.util.Collections.synchronizedList(new ArrayList<>(clients));
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicLong waveStartNanos = new AtomicLong(0L);

        for (int i = 1; i <= clients; i++) {
            final int clientIdx = i;
            Thread.ofVirtual().name("conc-client-" + clientIdx).start(() -> {
                long startOffsetMs = 0L;
                long endOffsetMs = 0L;
                String jobId = null;
                String status = "FAILED";
                boolean verified = false;
                String error = null;
                try {
                    startLatch.await();
                    startOffsetMs = Math.max(0L, (System.nanoTime() - waveStartNanos.get()) / 1_000_000L);
                    Path clientOut = sessionDir.resolve("output").resolve("client_" + clientIdx);
                    GuiClientService.JobResult res = GuiClientService.runJob(
                            cfg.host(),
                            cfg.rmiPort(),
                            req,
                            inputFiles,
                            clientOut,
                            msg -> addLog("INFO", "Cliente-" + clientIdx, msg),
                            null);
                    endOffsetMs = Math.max(startOffsetMs, (System.nanoTime() - waveStartNanos.get()) / 1_000_000L);
                    jobId = res.ticket().getJobId();
                    status = "COMPLETED";
                    verified = res.report() == null || res.report().verified();
                    succeeded.incrementAndGet();
                } catch (Exception ex) {
                    endOffsetMs = Math.max(startOffsetMs, (System.nanoTime() - waveStartNanos.get()) / 1_000_000L);
                    error = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    failed.incrementAndGet();
                    addLog("ERROR", "Cliente-" + clientIdx, error);
                } finally {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("clientIndex", clientIdx);
                    item.put("startOffsetMs", startOffsetMs);
                    item.put("endOffsetMs", endOffsetMs);
                    item.put("durationMs", Math.max(0L, endOffsetMs - startOffsetMs));
                    item.put("jobId", jobId);
                    item.put("status", status);
                    item.put("verified", verified);
                    item.put("error", error);
                    items.add(item);
                    doneLatch.countDown();
                }
            });
        }

        waveStartNanos.set(System.nanoTime());
        startLatch.countDown();
        doneLatch.await();
        long waveElapsedMs = Math.max(0L, (System.nanoTime() - waveStartNanos.get()) / 1_000_000L);

        List<Map<String, Object>> sortedItems = new ArrayList<>(items);
        sortedItems.sort(Comparator.comparingInt(m -> ((Number) m.get("clientIndex")).intValue()));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("sessionId", sessionId);
        res.put("waveElapsedMs", waveElapsedMs);
        res.put("maxConcurrentConfigured", cfg.maxConcurrent());
        res.put("clientsRequested", clients);
        res.put("succeeded", succeeded.get());
        res.put("failed", failed.get());
        res.put("items", sortedItems);
        return res;
    }

    // =========================================================================
    // STATIC SESSION FILE SERVING WITH PATH TRAVERSAL PROTECTION
    // =========================================================================

    private void handleFilesGet(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, errorMap("Metodo nao permitido"));
                return;
            }
            String uriPath = exchange.getRequestURI().getPath();
            String prefix = "/api/files/";
            if (!uriPath.startsWith(prefix)) {
                sendJson(exchange, 400, errorMap("Caminho invalido."));
                return;
            }
            String rest = uriPath.substring(prefix.length());
            String[] parts = rest.split("/", -1);
            if (parts.length != 3) {
                sendJson(exchange, 400, errorMap("Formato esperado: /api/files/{sessionId}/{slot}/{fileName}"));
                return;
            }

            String sessionId = parts[0];
            String slot = parts[1];
            String fileName = parts[2];

            try {
                validateSafeToken(sessionId, "sessionId");
                if (!VALID_SLOTS.contains(slot)) {
                    throw new ApiException(400, "Slot invalido: " + slot);
                }
                validateSafeToken(fileName, "fileName");
            } catch (ApiException ex) {
                sendJson(exchange, ex.statusCode, errorMap(ex.getMessage()));
                return;
            }

            Path root = sessionsDir.toAbsolutePath().normalize();
            Path resolved = root.resolve(sessionId).resolve(slot).resolve(fileName).normalize();
            if (!resolved.startsWith(root)) {
                sendJson(exchange, 400, errorMap("Caminho fora do diretorio permitido."));
                return;
            }
            if (!Files.isRegularFile(resolved)) {
                sendJson(exchange, 404, errorMap("Arquivo nao encontrado."));
                return;
            }

            String lower = fileName.toLowerCase(Locale.ROOT);
            String contentType;
            if (lower.endsWith(".png")) {
                contentType = "image/png";
            } else if (lower.endsWith(".csv")) {
                contentType = "text/csv; charset=utf-8";
            } else if (lower.endsWith(".txt")) {
                contentType = "text/plain; charset=utf-8";
            } else {
                contentType = "application/octet-stream";
            }

            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Cache-Control", "no-store");
            if ("1".equals(query.get("download"))) {
                headers.set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            }

            long size = Files.size(resolved);
            exchange.sendResponseHeaders(200, size);
            try (InputStream in = Files.newInputStream(resolved);
                 OutputStream out = exchange.getResponseBody()) {
                byte[] buf = new byte[STREAM_BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    // =========================================================================
    // COORDINATOR LIFECYCLE & HELPERS
    // =========================================================================

    private void tryAutoStartCoordinator() {
        ServerConfig cfg = config;
        try {
            startOrRestartCoordinator(cfg);
            performPing(cfg.host(), cfg.rmiPort());
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            addLog("ERROR", "Coordinator", "Falha ao iniciar coordenador automatico: " + msg);
        }
    }

    private void startOrRestartCoordinator(ServerConfig cfg) throws Exception {
        synchronized (coordinatorLock) {
            if (embeddedServer != null) {
                addLog("INFO", "Coordinator", "Reiniciando coordenador local...");
                try {
                    embeddedServer.close();
                } catch (Exception ignored) {
                }
                embeddedServer = null;
            }
            addLog("INFO", "Coordinator", String.format(Locale.ROOT,
                    "Iniciando Coordenador (%s | RMI:%d | TCP:%d | maxConc:%d | mpi:%s)...",
                    cfg.host(), cfg.rmiPort(), cfg.tcpPort(), cfg.maxConcurrent(), cfg.mpiDev()));
            CoordinatorMain.EmbeddedServer srv = CoordinatorMain.startServer(
                    cfg.host(),
                    cfg.rmiPort(),
                    cfg.tcpPort(),
                    cfg.maxConcurrent(),
                    workspaceBaseDir.resolve("server"),
                    cfg.mpiDev(),
                    null,
                    null);
            embeddedServer = srv;
            addLog("INFO", "Coordinator", String.format(Locale.ROOT,
                    "Coordenador ativo: RMI=%s:%d TCP=%d",
                    srv.getHost(), srv.getRmiPort(), srv.getTcpPort()));
        }
    }

    private void stopCoordinatorInternal() {
        synchronized (coordinatorLock) {
            if (embeddedServer != null) {
                addLog("INFO", "Coordinator", "Encerrando Coordenador Local...");
                try {
                    embeddedServer.close();
                } catch (Exception ignored) {
                }
                embeddedServer = null;
                addLog("INFO", "Coordinator", "Coordenador Local parado.");
            }
        }
    }

    private PingSnapshot performPing(String host, int rmiPort) {
        String now = LocalTime.now().format(TIME_FMT);
        long startNs = System.nanoTime();
        try {
            GuiClientService.PingResult pr = GuiClientService.ping(host, rmiPort);
            PingSnapshot snap = new PingSnapshot(true, pr.response(), pr.latencyMs(), now, null);
            lastPing = snap;
            addLog("INFO", "RMI", String.format(Locale.ROOT,
                    "Ping OK (%s:%d): %s (%d ms)", host, rmiPort, pr.response(), pr.latencyMs()));
            return snap;
        } catch (Exception ex) {
            long latencyMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            PingSnapshot snap = new PingSnapshot(false, null, latencyMs, now, msg);
            lastPing = snap;
            addLog("ERROR", "RMI", String.format(Locale.ROOT,
                    "Falha Ping (%s:%d): %s", host, rmiPort, msg));
            return snap;
        }
    }

    private ServerConfig parseAndApplyConfig(Map<String, Object> body) throws ApiException {
        ServerConfig cur = config;
        String host = getString(body, "host", cur.host()).trim();
        if (host.isEmpty()) {
            throw new ApiException(400, "host nao pode ser vazio.");
        }
        int rmiPort = getInt(body, "rmiPort", cur.rmiPort());
        int tcpPort = getInt(body, "tcpPort", cur.tcpPort());
        int maxConcurrent = getInt(body, "maxConcurrent", cur.maxConcurrent());
        String mpiDevRaw = getString(body, "mpiDev", cur.mpiDev());

        validatePortRange(rmiPort, "rmiPort");
        validatePortRange(tcpPort, "tcpPort");
        validateRange(maxConcurrent, 1, 16, "maxConcurrent");
        String mpiDev = normalizeMpiDev(mpiDevRaw);

        ServerConfig updated = new ServerConfig(host, rmiPort, tcpPort, maxConcurrent, mpiDev);
        config = updated;
        return updated;
    }

    private Map<String, Object> buildStatusMap() {
        ServerConfig cfg = config;
        CoordinatorMain.EmbeddedServer srv = embeddedServer;
        boolean running = srv != null && srv.isRunning();

        Map<String, Object> cfgMap = new LinkedHashMap<>();
        cfgMap.put("host", cfg.host());
        cfgMap.put("rmiPort", cfg.rmiPort());
        cfgMap.put("tcpPort", cfg.tcpPort());
        cfgMap.put("maxConcurrent", cfg.maxConcurrent());
        cfgMap.put("mpiDev", cfg.mpiDev());

        int logCount;
        synchronized (logLock) {
            logCount = logBuffer.size();
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("ok", true);
        status.put("config", cfgMap);
        status.put("embeddedRunning", running);
        status.put("embeddedHost", running ? srv.getHost() : null);
        status.put("embeddedRmiPort", running ? srv.getRmiPort() : null);
        status.put("embeddedTcpPort", running ? srv.getTcpPort() : null);
        status.put("openClAvailable", openClAvailable);
        status.put("cpuCores", cpuCores);
        status.put("lastPing", lastPing != null ? pingToMap(lastPing) : null);
        status.put("logCount", logCount);
        return status;
    }

    private static Map<String, Object> pingToMap(PingSnapshot snap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", snap.ok());
        map.put("response", snap.response());
        map.put("latencyMs", snap.latencyMs());
        map.put("checkedAt", snap.checkedAt());
        map.put("error", snap.error());
        return map;
    }

    private static Map<String, Object> benchmarkRowToMap(GuiClientService.BenchmarkRow row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionName", row.versionName());
        m.put("workers", row.workers());
        m.put("computeMs", row.computeMs());
        m.put("totalMs", row.totalMs());
        m.put("speedup", row.speedup());
        m.put("efficiencyPct", row.efficiencyPct());
        m.put("diffPixels", row.diffPixels());
        m.put("verified", row.verified());
        m.put("notes", row.notes());
        return m;
    }

    private static GuiClientService.BenchmarkRow selectWinnerRow(List<GuiClientService.BenchmarkRow> rows) {
        GuiClientService.BenchmarkRow best = null;
        for (GuiClientService.BenchmarkRow row : rows) {
            if (!row.verified()) {
                continue;
            }
            if (best == null) {
                best = row;
                continue;
            }
            if (row.computeMs() < best.computeMs()) {
                best = row;
            } else if (row.computeMs() == best.computeMs()
                    && "SEQUENTIAL".equalsIgnoreCase(best.versionName())
                    && !"SEQUENTIAL".equalsIgnoreCase(row.versionName())) {
                best = row;
            }
        }
        return best;
    }

    private void addLog(String level, String source, String message) {
        String time = LocalTime.now().format(TIME_FMT);
        long id = logSeq.incrementAndGet();
        LogEntry entry = new LogEntry(id, time, level, source, message == null ? "" : message);
        synchronized (logLock) {
            if (logBuffer.size() >= MAX_LOG_ENTRIES) {
                logBuffer.removeFirst();
            }
            logBuffer.addLast(entry);
        }
    }

    private String allocateSessionId(String prefix) {
        return prefix + "_" + System.currentTimeMillis() + "_" + sessionSeq.incrementAndGet();
    }

    private Path resolveSessionDir(String sessionId) throws ApiException {
        Path root = sessionsDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(sessionId).normalize();
        if (!resolved.startsWith(root)) {
            throw new ApiException(400, "sessionId invalido.");
        }
        return resolved;
    }

    private static Path findFirstPngInDir(Path inputDir) throws IOException {
        try (Stream<Path> stream = Files.list(inputDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static long streamUploadToFile(InputStream in, Path target) throws IOException, ApiException {
        long total = 0L;
        byte[] buf = new byte[STREAM_BUFFER_SIZE];
        try {
            try (OutputStream out = Files.newOutputStream(target)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_UPLOAD_BYTES) {
                        throw new ApiException(413, "Arquivo excede o limite de 32 MB.");
                    }
                    out.write(buf, 0, n);
                }
            }
            return total;
        } catch (IOException | ApiException ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
    }

    private static String sanitizeUploadFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "upload.png";
        }
        String trimmed = raw.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String base = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        StringBuilder sb = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String cleaned = sb.toString().replace("..", "_");
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isEmpty() ? "upload.png" : cleaned;
    }

    private static void validateSafeToken(String value, String fieldName) throws ApiException {
        if (value == null || value.isEmpty() || value.contains("..") || !SAFE_NAME_PATTERN.matcher(value).matches()) {
            throw new ApiException(400, fieldName + " invalido.");
        }
    }

    private static String buildFileUrl(String sessionId, String slot, String fileName) {
        return "/api/files/" + sessionId + "/" + slot + "/" + fileName;
    }

    private static int validatePort(int port, String name, int fallback) {
        int effective = port <= 0 ? fallback : port;
        if (effective < 1 || effective > 65535) {
            throw new IllegalArgumentException(name + " must be in [1, 65535]: " + effective);
        }
        return effective;
    }

    private static void validatePortRange(int port, String name) throws ApiException {
        if (port < 1 || port > 65535) {
            throw new ApiException(400, name + " deve estar entre 1 e 65535.");
        }
    }

    private static void validateRange(int value, int min, int max, String name) throws ApiException {
        if (value < min || value > max) {
            throw new ApiException(400, name + " deve estar entre " + min + " e " + max + ".");
        }
    }

    private static int clampOrValidate(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "]: " + value);
        }
        return value;
    }

    private static String normalizeMpiDev(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("mpiDev deve ser 'multicore' ou 'niodev'.");
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (!"multicore".equals(lower) && !"niodev".equals(lower)) {
            throw new IllegalArgumentException("mpiDev invalido (use 'multicore' ou 'niodev'): " + value);
        }
        return lower;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String val = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(val, StandardCharsets.UTF_8));
        }
        return map;
    }

    // =========================================================================
    // HTTP JSON TRANSPORT + MINI JSON PARSER / ENCODER
    // =========================================================================

    private Map<String, Object> readJsonObjectBody(HttpExchange exchange) throws IOException, ApiException {
        Headers headers = exchange.getRequestHeaders();
        String lenHeader = headers.getFirst("Content-Length");
        if (lenHeader != null && !lenHeader.isBlank()) {
            try {
                if (Long.parseLong(lenHeader.trim()) > MAX_JSON_BYTES) {
                    throw new ApiException(413, "Corpo JSON excede o limite de 1 MB.");
                }
            } catch (NumberFormatException ignored) {
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0L;
        InputStream in = exchange.getRequestBody();
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_JSON_BYTES) {
                throw new ApiException(413, "Corpo JSON excede o limite de 1 MB.");
            }
            baos.write(buf, 0, n);
        }
        String text = baos.toString(StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return Map.of();
        }
        try {
            return new JsonParser(text).parseObject();
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "JSON invalido: " + ex.getMessage());
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] bytes = encodeJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> errorMap(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", message == null ? "Erro interno" : message);
        return map;
    }

    private static String getRequiredString(Map<String, Object> map, String key) throws ApiException {
        Object val = map.get(key);
        if (!(val instanceof String s) || s.isBlank()) {
            throw new ApiException(400, "Campo obrigatorio ausente ou invalido: " + key);
        }
        return s.trim();
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) throws ApiException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return defaultVal;
        }
        Object val = map.get(key);
        if (val instanceof String s) {
            return s.isBlank() ? defaultVal : s;
        }
        throw new ApiException(400, "Campo '" + key + "' deve ser string.");
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) throws ApiException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return defaultVal;
        }
        Object val = map.get(key);
        if (val instanceof Number num) {
            double d = num.doubleValue();
            if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE || Math.rint(d) != d) {
                throw new ApiException(400, "Campo '" + key + "' deve ser inteiro.");
            }
            return num.intValue();
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ex) {
                throw new ApiException(400, "Campo '" + key + "' deve ser inteiro.");
            }
        }
        throw new ApiException(400, "Campo '" + key + "' deve ser inteiro.");
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) throws ApiException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return defaultVal;
        }
        Object val = map.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return false;
            }
        }
        throw new ApiException(400, "Campo '" + key + "' deve ser booleano.");
    }

    static String encodeJson(Object value) {
        StringBuilder sb = new StringBuilder(128);
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> appendJsonString(sb, s);
            case Boolean b -> sb.append(b.booleanValue() ? "true" : "false");
            case Double d -> {
                if (d.isNaN() || d.isInfinite()) {
                    sb.append("null");
                } else {
                    sb.append(d);
                }
            }
            case Float f -> {
                if (f.isNaN() || f.isInfinite()) {
                    sb.append("null");
                } else {
                    sb.append(f);
                }
            }
            case Number n -> sb.append(n);
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    appendJsonString(sb, String.valueOf(entry.getKey()));
                    sb.append(':');
                    appendJson(sb, entry.getValue());
                }
                sb.append('}');
            }
            case Iterable<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    appendJson(sb, item);
                }
                sb.append(']');
            }
            default -> appendJsonString(sb, String.valueOf(value));
        }
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
        }

        Map<String, Object> parseObject() {
            skipWhitespace();
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                skipWhitespace();
                ensureEnd();
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object val = parseFlatValue();
                map.put(key, val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    break;
                } else {
                    throw new IllegalArgumentException("Esperado ',' ou '}' na posicao " + pos);
                }
            }
            skipWhitespace();
            ensureEnd();
            return map;
        }

        private Object parseFlatValue() {
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Fim inesperado do JSON");
            }
            char c = input.charAt(pos);
            if (c == '"') {
                return parseString();
            }
            if (c == 't' && input.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && input.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (c == 'n' && input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw new IllegalArgumentException("Valor JSON inesperado na posicao " + pos);
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= input.length()) {
                        throw new IllegalArgumentException("Escape incompleto");
                    }
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) {
                                throw new IllegalArgumentException("Escape unicode incompleto");
                            }
                            String hex = input.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new IllegalArgumentException("Escape unicode invalido: " + hex);
                            }
                        }
                        default -> throw new IllegalArgumentException("Escape invalido: \\" + esc);
                    }
                } else {
                    if (c < 0x20) {
                        throw new IllegalArgumentException("Caractere de controle nao escapado em string");
                    }
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("String JSON nao terminada");
        }

        private Number parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new IllegalArgumentException("Numero invalido na posicao " + start);
            }
            if (input.charAt(pos) == '0') {
                pos++;
            } else {
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            boolean isDecimal = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Fracao decimal invalida na posicao " + start);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                    throw new IllegalArgumentException("Expoente invalido na posicao " + start);
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            String token = input.substring(start, pos);
            try {
                if (isDecimal) {
                    return Double.valueOf(token);
                }
                return Long.valueOf(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Numero fora de faixa: " + token);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private void expect(char expected) {
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new IllegalArgumentException("Esperado '" + expected + "' na posicao " + pos);
            }
            pos++;
        }

        private boolean peek(char ch) {
            return pos < input.length() && input.charAt(pos) == ch;
        }

        private void ensureEnd() {
            if (pos != input.length()) {
                throw new IllegalArgumentException("Caracteres extras apos JSON na posicao " + pos);
            }
        }
    }
}
