package estudio;

import estudio.core.DatasetGenerator;
import estudio.engine.OpenClGpuEngine;
import estudio.gui.GuiClientService;
import estudio.model.BenchmarkReport;
import estudio.model.ExecutionMode;
import estudio.model.FilterType;
import estudio.model.JobRequest;
import estudio.net.CoordinatorMain;
import estudio.net.TcpProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationGuiTest {

    private static final String HOST = "127.0.0.1";
    private static final int MAGENTA_ARGB = 0xFFFF00FF;

    @TempDir
    Path tempDir;

    @Test
    void testParseBenchmarkCsvAndDiffHeatmap() {
        List<BenchmarkReport.Entry> entries = List.of(
                new BenchmarkReport.Entry(
                        "SEQUENTIAL",
                        1,
                        120L,
                        125L,
                        1.0,
                        100.0,
                        0L,
                        true,
                        "baseline, \"ref\""),
                new BenchmarkReport.Entry(
                        "THREADS_ROWS",
                        4,
                        40L,
                        45L,
                        3.0,
                        75.0,
                        0L,
                        true,
                        "ok, sem \"erros\", 4T"));

        BenchmarkReport report = new BenchmarkReport(FilterType.SOBEL, 2, 64, 64, entries);
        String csv = report.toCsv();

        List<GuiClientService.BenchmarkRow> rows = GuiClientService.parseBenchmarkCsv(csv);
        assertEquals(2, rows.size());

        GuiClientService.BenchmarkRow row0 = rows.get(0);
        assertEquals("SEQUENTIAL", row0.versionName());
        assertEquals(1, row0.workers());
        assertEquals(120L, row0.computeMs());
        assertEquals(125L, row0.totalMs());
        assertEquals(1.0, row0.speedup(), 1e-4);
        assertEquals(100.0, row0.efficiencyPct(), 1e-2);
        assertEquals(0L, row0.diffPixels());
        assertTrue(row0.verified());
        assertEquals("baseline, \"ref\"", row0.notes());

        GuiClientService.BenchmarkRow row1 = rows.get(1);
        assertEquals("THREADS_ROWS", row1.versionName());
        assertEquals(4, row1.workers());
        assertEquals(40L, row1.computeMs());
        assertEquals(45L, row1.totalMs());
        assertEquals(3.0, row1.speedup(), 1e-4);
        assertEquals(75.0, row1.efficiencyPct(), 1e-2);
        assertEquals(0L, row1.diffPixels());
        assertTrue(row1.verified());
        assertEquals("ok, sem \"erros\", 4T", row1.notes());

        BufferedImage a = createUniformImage(8, 8, 0xFF102030);
        BufferedImage b = createUniformImage(8, 8, 0xFF102030);

        GuiClientService.DiffComparison equalComparison = GuiClientService.buildDiffHeatmap(a, b);
        assertEquals(0L, equalComparison.diffPixels());
        assertEquals(64L, equalComparison.totalPixels());
        assertNotNull(equalComparison.heatmapImage());
        assertEquals(8, equalComparison.heatmapImage().getWidth());
        assertEquals(8, equalComparison.heatmapImage().getHeight());

        b.setRGB(3, 5, 0xFFAA5500);
        GuiClientService.DiffComparison diffComparison = GuiClientService.buildDiffHeatmap(a, b);
        assertEquals(1L, diffComparison.diffPixels());
        assertEquals(64L, diffComparison.totalPixels());
        assertEquals(MAGENTA_ARGB, diffComparison.heatmapImage().getRGB(3, 5));
    }

    @Test
    void testEmbeddedServerAndGuiClientServiceEndToEnd() throws Exception {
        int rmiPort = findFreePort();
        int tcpPort = findFreePort();
        while (tcpPort == rmiPort) {
            tcpPort = findFreePort();
        }

        Path serverWorkDir = tempDir.resolve("server-work");
        Path inDir = tempDir.resolve("in");
        Path outDir = tempDir.resolve("out");

        CoordinatorMain.EmbeddedServer serverRef;
        try (var server = CoordinatorMain.startServer(
                HOST, rmiPort, tcpPort, 2, serverWorkDir, "multicore", null, null)) {
            serverRef = server;
            assertTrue(server.isRunning());

            GuiClientService.PingResult ping = GuiClientService.ping(HOST, rmiPort);
            assertNotNull(ping);
            assertNotNull(ping.response());
            assertFalse(ping.response().isBlank());

            List<Path> inputs = DatasetGenerator.generateDataset(inDir, 2, 32, 32);
            assertEquals(2, inputs.size());

            List<String> logs = Collections.synchronizedList(new ArrayList<>());
            List<TcpProtocol.ProgressFrame> progress = Collections.synchronizedList(new ArrayList<>());

            GuiClientService.JobResult result = GuiClientService.runJob(
                    HOST,
                    rmiPort,
                    new JobRequest(FilterType.SOBEL, ExecutionMode.BENCHMARK_ALL, 2, 32, 2),
                    inputs,
                    outDir,
                    logs::add,
                    progress::add);

            assertNotNull(result);
            assertNotNull(result.report());
            assertTrue(result.parsedRows().size() >= 5,
                    "Expected at least 5 engine rows, got " + result.parsedRows().size());

            boolean openClAvailable = OpenClGpuEngine.isOpenClAvailable();
            Map<String, GuiClientService.BenchmarkRow> rowsByVersion = new HashMap<>();
            for (GuiClientService.BenchmarkRow row : result.parsedRows()) {
                rowsByVersion.put(row.versionName(), row);
                if (OpenClGpuEngine.ENGINE_NAME.equals(row.versionName()) && !openClAvailable) {
                    assertTrue(row.verified() || row.notes().startsWith("Indisponivel"),
                            "Unexpected GPU_OPENCL failure when OpenCL unavailable: " + row);
                    if (row.verified()) {
                        assertEquals(0L, row.diffPixels(),
                                "Non-zero diffPixels for engine " + row.versionName());
                    }
                } else {
                    assertEquals(0L, row.diffPixels(),
                            "Non-zero diffPixels for engine " + row.versionName());
                    assertTrue(row.verified(),
                            "Engine not verified: " + row.versionName() + " notes=" + row.notes());
                }
            }
            for (String required : List.of(
                    "SEQUENTIAL", "THREADS_ROWS", "THREADS_BLOCKS", "MPJ_MULTICORE", OpenClGpuEngine.ENGINE_NAME)) {
                assertTrue(rowsByVersion.containsKey(required), "Missing benchmark row: " + required);
            }

            assertEquals(2, result.outputFiles().size());
            for (Path outFile : result.outputFiles()) {
                assertTrue(Files.isRegularFile(outFile), "Missing output image: " + outFile);
            }
            assertTrue(Files.isRegularFile(outDir.resolve("benchmark_report.txt")),
                    "Missing benchmark_report.txt in " + outDir);
            assertTrue(Files.isRegularFile(outDir.resolve("benchmark_report.csv")),
                    "Missing benchmark_report.csv in " + outDir);
            assertFalse(logs.isEmpty());
            assertFalse(progress.isEmpty());
        }

        assertFalse(serverRef.isRunning());
    }

    @Test
    void testEnsurePngInputsResolvesCollisionsAndConvertsJpeg(@TempDir Path tempDir) throws IOException {
        Path dirA = tempDir.resolve("dirA");
        Path dirB = tempDir.resolve("dirB");
        Path stagingDir = tempDir.resolve("staging");
        Files.createDirectories(dirA);
        Files.createDirectories(dirB);

        Path pngA = dirA.resolve("foto.png");
        Path jpgA = dirA.resolve("foto.jpg");
        Path pngB = dirB.resolve("foto.png");

        BufferedImage imgPngA = createUniformRgbImage(8, 8, 0xFF112233);
        BufferedImage imgJpgA = createUniformRgbImage(10, 10, 0xFF445566);
        BufferedImage imgPngB = createUniformRgbImage(12, 12, 0xFF778899);

        assertTrue(ImageIO.write(imgPngA, "png", pngA.toFile()));
        assertTrue(ImageIO.write(imgJpgA, "jpg", jpgA.toFile()));
        assertTrue(ImageIO.write(imgPngB, "png", pngB.toFile()));

        List<Path> prepared = GuiClientService.ensurePngInputs(List.of(pngA, jpgA, pngB), stagingDir);
        assertEquals(3, prepared.size());

        Set<String> distinctNames = new HashSet<>();
        List<Integer> widths = new ArrayList<>();
        for (Path outPath : prepared) {
            assertTrue(Files.isRegularFile(outPath), "Missing prepared PNG: " + outPath);
            String fileName = outPath.getFileName().toString();
            assertTrue(fileName.toLowerCase(Locale.ROOT).endsWith(".png"),
                    "Expected .png output file, got " + fileName);
            assertTrue(distinctNames.add(fileName.toLowerCase(Locale.ROOT)),
                    "Colliding output filename: " + fileName);

            BufferedImage decoded = ImageIO.read(outPath.toFile());
            assertNotNull(decoded, "Unreadable prepared PNG: " + outPath);
            widths.add(decoded.getWidth());
        }

        assertEquals(3, distinctNames.size());
        assertEquals(List.of(8, 10, 12), widths);
    }

    private static BufferedImage createUniformRgbImage(int width, int height, int rgb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private static BufferedImage createUniformImage(int width, int height, int argb) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return ss.getLocalPort();
        }
    }
}
