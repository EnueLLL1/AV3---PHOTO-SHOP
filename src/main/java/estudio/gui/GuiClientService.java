package estudio.gui;

import estudio.model.JobRequest;
import estudio.model.JobStatus;
import estudio.model.JobTicket;
import estudio.net.CoordinatorRmiService;
import estudio.net.TcpProtocol;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class GuiClientService {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int MAGENTA_DIFF_ARGB = 0xFFFF00FF;

    public record PingResult(String response, long latencyMs) {
    }

    public record BenchmarkRow(
            String versionName,
            int workers,
            long computeMs,
            long totalMs,
            double speedup,
            double efficiencyPct,
            long diffPixels,
            boolean verified,
            String notes) {
    }

    public record JobResult(
            JobTicket ticket,
            TcpProtocol.ReportFrame report,
            List<Path> outputFiles,
            long elapsedMs,
            List<BenchmarkRow> parsedRows) {
    }

    public record DiffComparison(BufferedImage heatmapImage, long diffPixels, long totalPixels) {
    }

    private GuiClientService() {
    }

    public static PingResult ping(String host, int rmiPort) throws Exception {
        String effectiveHost = normalizeHost(host);
        long startNanos = System.nanoTime();
        Registry registry = LocateRegistry.getRegistry(effectiveHost, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) registry.lookup(CoordinatorRmiService.BINDING_NAME);
        String response = rmi.ping();
        long latencyMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        return new PingResult(response, latencyMs);
    }

    public static JobResult runJob(
            String host,
            int rmiPort,
            JobRequest request,
            List<Path> inputFiles,
            Path outputDir,
            Consumer<String> logSink,
            Consumer<TcpProtocol.ProgressFrame> progressSink) throws Exception {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(inputFiles, "inputFiles must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        if (inputFiles.isEmpty()) {
            throw new IllegalArgumentException("inputFiles must not be empty");
        }

        String effectiveHost = normalizeHost(host);
        Files.createDirectories(outputDir);
        long startNanos = System.nanoTime();

        Registry registry = LocateRegistry.getRegistry(effectiveHost, rmiPort);
        CoordinatorRmiService rmi = (CoordinatorRmiService) registry.lookup(CoordinatorRmiService.BINDING_NAME);
        JobTicket ticket = rmi.submitJob(request);

        emitLog(logSink, String.format(Locale.ROOT,
                "Job submetido via RMI (%s:%d) | jobId=%s | fila=%d | tcp=%s:%d",
                effectiveHost, rmiPort, ticket.getJobId(), ticket.getQueuePosition(),
                ticket.getSocketHost(), ticket.getSocketPort()));
        emitLog(logSink, String.format(Locale.ROOT,
                "Enviando %d arquivo(s) de entrada...", inputFiles.size()));

        List<Path> outputFiles = new ArrayList<>();
        TcpProtocol.ReportFrame reportFrame = null;
        JobStatus.State lastLoggedState = null;
        int lastLoggedPct = -1;

        try (Socket socket = new Socket(ticket.getSocketHost(), ticket.getSocketPort());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

            TcpProtocol.sendUploadBatch(out, ticket.getJobId(), inputFiles);

            while (true) {
                byte frameType = in.readByte();
                switch (frameType) {
                    case TcpProtocol.FRAME_PROGRESS -> {
                        TcpProtocol.ProgressFrame p = TcpProtocol.readProgressPayload(in);
                        if (progressSink != null) {
                            progressSink.accept(p);
                        }
                        int total = Math.max(1, p.totalSteps());
                        int completed = Math.min(total, Math.max(0, p.completedSteps()));
                        int pct = (int) Math.round((completed * 100.0) / total);
                        boolean stateChanged = p.state() != lastLoggedState;
                        boolean milestone = stateChanged
                                || completed == 0
                                || completed >= total
                                || pct - lastLoggedPct >= 20;
                        if (milestone) {
                            emitLog(logSink, String.format(Locale.ROOT,
                                    "[%s] %d%% (%d/%d) - %s",
                                    p.state(), pct, completed, total, p.message()));
                            lastLoggedState = p.state();
                            lastLoggedPct = pct;
                        }
                    }
                    case TcpProtocol.FRAME_REPORT -> {
                        reportFrame = TcpProtocol.readReportPayload(in);
                        if (reportFrame.tableText() != null) {
                            Files.writeString(outputDir.resolve("benchmark_report.txt"),
                                    reportFrame.tableText(), StandardCharsets.UTF_8);
                        }
                        if (reportFrame.csvText() != null) {
                            Files.writeString(outputDir.resolve("benchmark_report.csv"),
                                    reportFrame.csvText(), StandardCharsets.UTF_8);
                        }
                        emitLog(logSink, String.format(Locale.ROOT,
                                "Relatorio recebido (verificado=%s, diffPixels=%d)",
                                reportFrame.verified(), reportFrame.diffPixels()));
                    }
                    case TcpProtocol.FRAME_IMAGE -> {
                        Path savedImage = TcpProtocol.readImageToDir(in, outputDir);
                        outputFiles.add(savedImage);
                    }
                    case TcpProtocol.FRAME_DONE -> {
                        String doneMsg = TcpProtocol.readDonePayload(in);
                        long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                        emitLog(logSink, String.format(Locale.ROOT,
                                "%s | Imagens recebidas: %d | Tempo total: %d ms",
                                doneMsg, outputFiles.size(), elapsedMs));
                        List<BenchmarkRow> parsedRows = reportFrame == null
                                ? List.of()
                                : parseBenchmarkCsv(reportFrame.csvText());
                        return new JobResult(ticket, reportFrame, List.copyOf(outputFiles), elapsedMs, parsedRows);
                    }
                    case TcpProtocol.FRAME_ERROR -> {
                        String err = TcpProtocol.readErrorPayload(in);
                        emitLog(logSink, "Erro reportado pelo servidor: " + err);
                        throw new IOException("Server error: " + err);
                    }
                    default -> throw new IOException(
                            "Unknown TCP frame type: 0x" + String.format(Locale.ROOT, "%02X", frameType));
                }
            }
        }
    }

    public static List<BenchmarkRow> parseBenchmarkCsv(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            return List.of();
        }

        List<List<String>> records = parseRfc4180Records(csvText);
        if (records.isEmpty()) {
            return List.of();
        }

        List<BenchmarkRow> rows = new ArrayList<>();
        boolean headerSkipped = false;
        for (List<String> record : records) {
            if (isEmptyRecord(record)) {
                continue;
            }
            if (!headerSkipped && "filter".equalsIgnoreCase(record.get(0).trim())) {
                headerSkipped = true;
                continue;
            }
            headerSkipped = true;
            if (record.size() < 12) {
                throw new IllegalArgumentException(
                        "Invalid benchmark CSV row: expected at least 12 columns, got " + record.size());
            }
            String versionName = record.get(4);
            int workers = Integer.parseInt(record.get(5).trim());
            long computeMs = Long.parseLong(record.get(6).trim());
            long totalMs = Long.parseLong(record.get(7).trim());
            double speedup = Double.parseDouble(record.get(8).trim());
            double efficiencyPct = Double.parseDouble(record.get(9).trim());
            long diffPixels = Long.parseLong(record.get(10).trim());
            boolean verified = Boolean.parseBoolean(record.get(11).trim());
            String notes = record.size() > 12 ? record.get(12) : "";
            rows.add(new BenchmarkRow(
                    versionName,
                    workers,
                    computeMs,
                    totalMs,
                    speedup,
                    efficiencyPct,
                    diffPixels,
                    verified,
                    notes));
        }
        return List.copyOf(rows);
    }

    public static DiffComparison buildDiffHeatmap(BufferedImage baseline, BufferedImage candidate) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        int width = baseline.getWidth();
        int height = baseline.getHeight();
        if (width != candidate.getWidth() || height != candidate.getHeight()) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: baseline=" + width + "x" + height
                            + ", candidate=" + candidate.getWidth() + "x" + candidate.getHeight());
        }

        int pixelCount = width * height;
        int[] basePixels = new int[pixelCount];
        int[] candPixels = new int[pixelCount];
        baseline.getRGB(0, 0, width, height, basePixels, 0, width);
        candidate.getRGB(0, 0, width, height, candPixels, 0, width);

        int[] heatmapPixels = new int[pixelCount];
        long diffPixels = 0L;
        for (int i = 0; i < pixelCount; i++) {
            int baseArgb = basePixels[i];
            if (baseArgb != candPixels[i]) {
                heatmapPixels[i] = MAGENTA_DIFF_ARGB;
                diffPixels++;
            } else {
                int r = ((baseArgb >> 16) & 0xFF) / 5;
                int g = Math.min(255, 10 + (((baseArgb >> 8) & 0xFF) / 3));
                int b = (baseArgb & 0xFF) / 5;
                heatmapPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        BufferedImage heatmapImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        heatmapImage.setRGB(0, 0, width, height, heatmapPixels, 0, width);
        return new DiffComparison(heatmapImage, diffPixels, pixelCount);
    }

    public static List<Path> ensurePngInputs(List<Path> selectedFiles, Path stagingDir) throws IOException {
        Objects.requireNonNull(selectedFiles, "selectedFiles must not be null");
        Objects.requireNonNull(stagingDir, "stagingDir must not be null");
        if (selectedFiles.isEmpty()) {
            return List.of();
        }

        List<Path> prepared = new ArrayList<>(selectedFiles.size());
        Set<String> usedStagedNames = new HashSet<>();
        boolean stagingCreated = false;

        for (Path rawFile : selectedFiles) {
            Path normalized = Objects.requireNonNull(rawFile, "selectedFiles element must not be null")
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("Input file does not exist or is not a regular file: " + normalized);
            }

            String fileName = normalized.getFileName().toString();
            String lowerName = fileName.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".png")) {
                if (!usedStagedNames.contains(lowerName)) {
                    usedStagedNames.add(lowerName);
                    prepared.add(normalized);
                    continue;
                }
                if (!stagingCreated) {
                    Files.createDirectories(stagingDir);
                    stagingCreated = true;
                }
                String baseName = stripExtension(fileName);
                Path target = allocateUniquePngPath(stagingDir, baseName, usedStagedNames);
                Files.copy(normalized, target);
                prepared.add(target.toAbsolutePath().normalize());
                continue;
            }

            BufferedImage decoded = ImageIO.read(normalized.toFile());
            if (decoded == null) {
                throw new IOException("File could not be read as a supported image: " + normalized);
            }

            if (!stagingCreated) {
                Files.createDirectories(stagingDir);
                stagingCreated = true;
            }

            String baseName = stripExtension(fileName);
            Path target = allocateUniquePngPath(stagingDir, baseName, usedStagedNames);
            if (!ImageIO.write(decoded, "png", target.toFile())) {
                throw new IOException("No PNG writer available to convert image: " + normalized);
            }
            prepared.add(target.toAbsolutePath().normalize());
        }

        return List.copyOf(prepared);
    }

    private static String normalizeHost(String host) {
        return (host == null || host.isBlank()) ? DEFAULT_HOST : host.trim();
    }

    private static void emitLog(Consumer<String> logSink, String message) {
        if (logSink != null) {
            logSink.accept(message);
        }
    }

    private static List<List<String>> parseRfc4180Records(String csvText) {
        List<List<String>> records = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStartedWithQuote = false;

        int len = csvText.length();
        for (int i = 0; i < len; i++) {
            char c = csvText.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csvText.charAt(i + 1) == '"') {
                        currentField.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentField.append(c);
                }
            } else {
                if (c == '"' && currentField.length() == 0 && !fieldStartedWithQuote) {
                    inQuotes = true;
                    fieldStartedWithQuote = true;
                } else if (c == ',') {
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                    fieldStartedWithQuote = false;
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < len && csvText.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                    fieldStartedWithQuote = false;
                    records.add(currentRow);
                    currentRow = new ArrayList<>();
                } else {
                    currentField.append(c);
                }
            }
        }

        if (currentField.length() > 0 || !currentRow.isEmpty() || fieldStartedWithQuote) {
            currentRow.add(currentField.toString());
            records.add(currentRow);
        }
        return records;
    }

    private static boolean isEmptyRecord(List<String> record) {
        return record.isEmpty() || (record.size() == 1 && record.get(0).isBlank());
    }

    private static String stripExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIdx);
    }

    private static Path allocateUniquePngPath(Path stagingDir, String baseName, Set<String> usedNames) {
        String safeBase = baseName.isBlank() ? "image" : baseName;
        String candidateName = safeBase + ".png";
        int counter = 1;
        while (usedNames.contains(candidateName.toLowerCase(Locale.ROOT))
                || Files.exists(stagingDir.resolve(candidateName))) {
            candidateName = safeBase + "_" + counter + ".png";
            counter++;
        }
        usedNames.add(candidateName.toLowerCase(Locale.ROOT));
        return stagingDir.resolve(candidateName);
    }
}
