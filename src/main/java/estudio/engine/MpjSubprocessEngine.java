package estudio.engine;

import estudio.model.FilterType;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class MpjSubprocessEngine implements ImageEngine {
    private static final int DEFAULT_NP = 4;
    private static final String DEFAULT_DEV_MODE = "multicore";

    private final int np;
    private final String devMode;
    private final String machinesFile;
    private final String mpjHome;
    private final String classpath;

    public MpjSubprocessEngine() {
        this(DEFAULT_NP, DEFAULT_DEV_MODE, null, null, null);
    }

    public MpjSubprocessEngine(int np, String devMode) {
        this(np, devMode, null, null, null);
    }

    public MpjSubprocessEngine(
            int np,
            String devMode,
            String machinesFile,
            String mpjHome,
            String classpath) {
        this.np = np <= 0 ? DEFAULT_NP : np;
        this.devMode = (devMode == null || devMode.isBlank())
                ? DEFAULT_DEV_MODE
                : devMode.trim().toLowerCase(Locale.ROOT);
        this.machinesFile = (machinesFile == null || machinesFile.isBlank())
                ? null
                : machinesFile.trim();
        this.mpjHome = resolveMpjHome(mpjHome);
        this.classpath = (classpath == null || classpath.isBlank())
                ? buildDefaultClasspath()
                : classpath;
    }

    @Override
    public EngineResult process(
            List<Path> inputFiles,
            Path outputDir,
            FilterType filter,
            ProgressListener listener) throws Exception {
        Objects.requireNonNull(inputFiles, "inputFiles must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(filter, "filter must not be null");

        Path outDirAbs = outputDir.toAbsolutePath().normalize();
        Files.createDirectories(outDirAbs);

        String engineName = "MPJ_" + devMode.toUpperCase(Locale.ROOT);
        if (inputFiles.isEmpty()) {
            return new EngineResult(
                    engineName,
                    np,
                    0L,
                    0L,
                    "MPJ Express dev=" + devMode + ", workers=" + np + ", images=0, filter=" + filter);
        }

        Path mpjHomePath = Path.of(mpjHome).toAbsolutePath().normalize();
        Path starterJar = mpjHomePath.resolve("lib").resolve("starter.jar");
        if (!Files.isRegularFile(starterJar)) {
            throw new IllegalStateException("MPJ Express starter.jar not found at: " + starterJar);
        }

        Path tempRoot = null;
        Path metricsFile = null;
        try {
            Path effectiveInputDir = tryReuseSharedInputDir(inputFiles);
            if (effectiveInputDir == null) {
                tempRoot = Files.createTempDirectory("estudio-mpj-");
                effectiveInputDir = stageInputFiles(tempRoot.resolve("input"), inputFiles);
                metricsFile = tempRoot.resolve("metrics.properties");
            } else {
                metricsFile = Files.createTempFile("estudio-mpj-metrics-", ".properties");
            }

            List<String> command = buildCommand(
                    starterJar,
                    effectiveInputDir.toAbsolutePath().normalize(),
                    outDirAbs,
                    filter,
                    metricsFile.toAbsolutePath().normalize());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("MPJ_HOME", mpjHomePath.toString());
            pb.redirectErrorStream(true);

            long totalStartNs = System.nanoTime();
            Process process = pb.start();
            StringBuilder outputLog = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLog.append(line).append(System.lineSeparator());
                    handleProgressLine(line, listener);
                }
            }

            int exitCode = process.waitFor();
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStartNs);

            if (exitCode != 0) {
                throw new IOException("MPJ subprocess failed with exit code " + exitCode + ":\n" + outputLog);
            }

            Properties metrics = loadMetrics(metricsFile);
            long computeMs = Long.parseLong(metrics.getProperty("computeMs", "0"));
            long kernelMaxMs = Long.parseLong(metrics.getProperty("kernelMaxMs", "0"));
            int workers = Integer.parseInt(metrics.getProperty("workers", Integer.toString(np)));

            String details = "MPJ Express dev=" + devMode
                    + ", workers=" + workers
                    + ", kernelMaxMs=" + kernelMaxMs
                    + ", filter=" + filter;
            return new EngineResult(engineName, workers, computeMs, totalMs, details);
        } finally {
            if (tempRoot != null) {
                deleteRecursively(tempRoot);
            } else if (metricsFile != null) {
                Files.deleteIfExists(metricsFile);
            }
        }
    }

    private List<String> buildCommand(
            Path starterJar,
            Path inputDir,
            Path outputDir,
            FilterType filter,
            Path metricsFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaExecutable());
        cmd.add("-Xmx2g");
        cmd.add("-jar");
        cmd.add(starterJar.toString());
        cmd.add("-np");
        cmd.add(Integer.toString(np));
        cmd.add("-dev");
        cmd.add(devMode);
        if (machinesFile != null) {
            cmd.add("-machinesfile");
            cmd.add(Path.of(machinesFile).toAbsolutePath().normalize().toString());
        }
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(MpjWorkerMain.class.getName());
        cmd.add("--inputDir=" + inputDir);
        cmd.add("--outputDir=" + outputDir);
        cmd.add("--filter=" + filter.name());
        cmd.add("--metricsFile=" + metricsFile);
        return cmd;
    }

    private static Path tryReuseSharedInputDir(List<Path> inputFiles) throws IOException {
        Path firstAbs = Objects.requireNonNull(inputFiles.get(0), "inputFiles element must not be null")
                .toAbsolutePath().normalize();
        Path parent = firstAbs.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }

        List<Path> normalizedInputs = new ArrayList<>(inputFiles.size());
        for (Path p : inputFiles) {
            Path abs = Objects.requireNonNull(p, "inputFiles element must not be null")
                    .toAbsolutePath().normalize();
            if (!parent.equals(abs.getParent())) {
                return null;
            }
            normalizedInputs.add(abs);
        }

        List<Path> dirImages = MpjWorkerMain.listSortedImages(parent);
        if (dirImages.size() != normalizedInputs.size()) {
            return null;
        }
        for (int i = 0; i < dirImages.size(); i++) {
            if (!dirImages.get(i).toAbsolutePath().normalize().equals(normalizedInputs.get(i))) {
                return null;
            }
        }
        return parent;
    }

    private static Path stageInputFiles(Path stagedDir, List<Path> inputFiles) throws IOException {
        Files.createDirectories(stagedDir);
        for (Path source : inputFiles) {
            Path absSource = Objects.requireNonNull(source, "inputFiles element must not be null")
                    .toAbsolutePath().normalize();
            Path target = stagedDir.resolve(absSource.getFileName().toString());
            if (Files.exists(target)) {
                throw new IllegalArgumentException("Duplicate input file name for MPJ staging: " + target.getFileName());
            }
            try {
                Files.createLink(target, absSource);
            } catch (IOException | UnsupportedOperationException e) {
                Files.copy(absSource, target);
            }
        }
        return stagedDir;
    }

    private static void handleProgressLine(String line, ProgressListener listener) {
        if (listener == null || !line.startsWith("MPJ_PROGRESS ")) {
            return;
        }
        String[] parts = line.split("\\s+", 4);
        if (parts.length == 4) {
            try {
                int step = Integer.parseInt(parts[1]);
                int total = Integer.parseInt(parts[2]);
                listener.onProgress(step, total, parts[3]);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static Properties loadMetrics(Path metricsFile) throws IOException {
        if (!Files.isRegularFile(metricsFile)) {
            throw new IOException("MPJ metrics file was not created: " + metricsFile);
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(metricsFile, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    private static String resolveMpjHome(String mpjHome) {
        if (mpjHome != null && !mpjHome.isBlank()) {
            return Path.of(mpjHome.trim()).toAbsolutePath().normalize().toString();
        }
        String env = System.getenv("MPJ_HOME");
        if (env != null && !env.isBlank() && Files.isDirectory(Path.of(env))) {
            return Path.of(env).toAbsolutePath().normalize().toString();
        }
        return Path.of("tools", "mpj").toAbsolutePath().normalize().toString();
    }

    private static String buildDefaultClasspath() {
        Set<String> entries = new LinkedHashSet<>();

        String currentCp = System.getProperty("java.class.path");
        if (currentCp != null && !currentCp.isBlank()) {
            for (String part : currentCp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!part.isBlank()) {
                    entries.add(Path.of(part).toAbsolutePath().normalize().toString());
                }
            }
        }

        Path outMain = Path.of("out", "main").toAbsolutePath().normalize();
        if (Files.isDirectory(outMain)) {
            entries.add(outMain.toString());
        }

        Path libDir = Path.of("lib").toAbsolutePath().normalize();
        if (Files.isDirectory(libDir)) {
            try (Stream<Path> jars = Files.list(libDir)) {
                jars.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> entries.add(p.toAbsolutePath().normalize().toString()));
            } catch (IOException ignored) {
            }
        }

        return String.join(File.pathSeparator, entries);
    }

    private static String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path candidate = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return "java";
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
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
}
