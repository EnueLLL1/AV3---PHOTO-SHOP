package estudio;

import estudio.engine.MpjSubprocessEngine;
import estudio.model.FilterType;
import estudio.core.DatasetGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpjSubprocessEngineBugReproductionTest {

    private static final int MAX_EXPECTED_LOG_CHARS = 70_000;

    @Test
    void subprocessExceedingTimeoutFailsInsteadOfBlockingIndefinitely(@TempDir Path tempDir) throws Exception {
        Path fakeMpjHome = createFakeMpjHome(tempDir);
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        DatasetGenerator.generateDataset(inputDir, 1, 16, 16);
        List<Path> inputs = listSortedPngs(inputDir);

        MpjSubprocessEngine engine = new MpjSubprocessEngine(1, "hang", null, fakeMpjHome.toString(), null, 500L);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                Exception ex = assertThrows(
                        Exception.class,
                        () -> engine.process(inputs, outputDir, FilterType.BLUR, null));
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
                assertTrue(msg.contains("timeout") || msg.contains("timed out"),
                        "Expected timeout error message, got: " + ex);
            });
        } finally {
            killChildIfAlive(outputDir.resolve("pid.txt"));
        }
    }

    @Test
    void interruptingCallerThreadForciblyDestroysChildProcess(@TempDir Path tempDir) throws Exception {
        Path fakeMpjHome = createFakeMpjHome(tempDir);
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        DatasetGenerator.generateDataset(inputDir, 1, 16, 16);
        List<Path> inputs = listSortedPngs(inputDir);

        MpjSubprocessEngine engine = new MpjSubprocessEngine(1, "hang", null, fakeMpjHome.toString(), null, 500L);
        Path pidFile = outputDir.resolve("pid.txt");
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread caller = new Thread(() -> {
            try {
                engine.process(inputs, outputDir, FilterType.BLUR, null);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "mpj-interrupt-test");

        try {
            caller.start();
            long childPid = waitForPid(pidFile, Duration.ofSeconds(5));
            ProcessHandle handle = ProcessHandle.of(childPid).orElse(null);
            assertTrue(handle != null && handle.isAlive(), "Fake child process should be running before interrupt");

            caller.interrupt();
            caller.join(2_000L);

            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (handle.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(25L);
            }
            assertFalse(handle.isAlive(),
                    "Child process (pid=" + childPid + ") must be forcibly destroyed when caller thread is interrupted");
        } finally {
            caller.interrupt();
            killChildIfAlive(pidFile);
        }
    }

    @Test
    void excessiveSubprocessOutputIsTruncatedToMaxLogBuffer(@TempDir Path tempDir) throws Exception {
        Path fakeMpjHome = createFakeMpjHome(tempDir);
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        DatasetGenerator.generateDataset(inputDir, 1, 16, 16);
        List<Path> inputs = listSortedPngs(inputDir);

        MpjSubprocessEngine engine = new MpjSubprocessEngine(1, "spam", null, fakeMpjHome.toString(), null);
        IOException ex = assertThrows(
                IOException.class,
                () -> engine.process(inputs, outputDir, FilterType.BLUR, null));

        String message = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(message.length() <= MAX_EXPECTED_LOG_CHARS,
                "Subprocess output log must be truncated to max buffer limit, but exception message length was "
                        + message.length());
    }

    private static Path createFakeMpjHome(Path tempDir) throws IOException {
        Path mpjHome = tempDir.resolve("fake-mpj");
        Path libDir = mpjHome.resolve("lib");
        Files.createDirectories(libDir);
        Path starterJar = libDir.resolve("starter.jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, MpjFakeStarterMain.class.getName());

        String classEntryName = MpjFakeStarterMain.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(starterJar), manifest);
             InputStream classBytes = MpjFakeStarterMain.class.getResourceAsStream("MpjFakeStarterMain.class")) {
            if (classBytes == null) {
                throw new IOException("Unable to locate compiled resource for " + classEntryName);
            }
            jos.putNextEntry(new JarEntry(classEntryName));
            classBytes.transferTo(jos);
            jos.closeEntry();
        }
        return mpjHome;
    }

    private static long waitForPid(Path pidFile, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    return Long.parseLong(text);
                }
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Timed out waiting for fake MPJ child process to write PID file: " + pidFile);
    }

    private static void killChildIfAlive(Path pidFile) {
        try {
            if (Files.isRegularFile(pidFile)) {
                String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    long pid = Long.parseLong(text);
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        } catch (Exception ignored) {
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
