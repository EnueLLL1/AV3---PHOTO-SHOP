package estudio.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebStudioServerTest {

    private static final String HOST = "127.0.0.1";

    @TempDir
    Path tempDir;

    @Test
    void testParseArgsValidAndInvalid() {
        Object defaults = PresentationApp.parseArgs(new String[0]);
        assertEquals(
                "AppConfig[autoServer=true, host=127.0.0.1, rmiPort=1099, tcpPort=9090, httpPort=8080, explicitHttpPort=false, noBrowser=false]",
                defaults.toString());

        Object nullArgs = PresentationApp.parseArgs(null);
        assertEquals(defaults, nullArgs);

        Object custom = PresentationApp.parseArgs(new String[]{
                "--no-auto-server",
                "--host", "10.0.0.5",
                "--rmi-port", "2099",
                "--tcp-port", "9191",
                "--http-port", "8181",
                "--no-browser"
        });
        assertEquals(
                "AppConfig[autoServer=false, host=10.0.0.5, rmiPort=2099, tcpPort=9191, httpPort=8181, explicitHttpPort=true, noBrowser=true]",
                custom.toString());

        assertThrows(IllegalArgumentException.class,
                () -> PresentationApp.parseArgs(new String[]{"--unknown-flag"}));
        assertThrows(IllegalArgumentException.class,
                () -> PresentationApp.parseArgs(new String[]{"--http-port", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> PresentationApp.parseArgs(new String[]{"--rmi-port", "70000"}));
        assertThrows(IllegalArgumentException.class,
                () -> PresentationApp.parseArgs(new String[]{"--host"}));
    }

    @Test
    void testWebStudioEndToEndApiAndStaticAssets() throws Exception {
        int rmiPort = findFreePort();
        int tcpPort = findFreePortDifferentFrom(rmiPort);

        try (WebStudioServer server = WebStudioServer.start(
                0, HOST, rmiPort, tcpPort, 2, "multicore", true, tempDir);
             HttpClient client = HttpClient.newBuilder()
                     .connectTimeout(Duration.ofSeconds(10))
                     .build()) {

            String baseUrl = server.getBaseUrl();
            assertTrue(server.isRunning());
            assertTrue(server.isCoordinatorRunning());

            // 1. Static assets
            HttpResponse<String> rootResp = getText(client, baseUrl + "/");
            assertEquals(200, rootResp.statusCode());
            assertHeaderContains(rootResp, "Content-Type", "text/html");
            assertHeaderEquals(rootResp, "X-Content-Type-Options", "nosniff");

            assertStaticAsset(client, baseUrl, "/styles.css", "/web/styles.css", "text/css");
            assertStaticAsset(client, baseUrl, "/app.js", "/web/app.js", "application/javascript");

            // 2. Status and ping
            HttpResponse<String> statusResp = getText(client, baseUrl + "/api/status");
            assertEquals(200, statusResp.statusCode());
            assertTrue(statusResp.body().contains("\"ok\":true"), statusResp.body());
            assertTrue(statusResp.body().contains("\"embeddedRunning\":true"), statusResp.body());

            HttpResponse<String> pingResp = postJson(client, baseUrl + "/api/ping", "{}");
            assertEquals(200, pingResp.statusCode());
            assertTrue(pingResp.body().contains("\"ok\":true"), pingResp.body());

            // 3. Studio generate + GET input PNG
            HttpResponse<String> genResp = postJson(client, baseUrl + "/api/studio/generate",
                    "{\"width\":32,\"height\":32}");
            assertEquals(200, genResp.statusCode());
            assertTrue(genResp.body().contains("\"ok\":true"), genResp.body());
            String sessionId = extractJsonString(genResp.body(), "sessionId");
            String inputUrl = extractJsonString(genResp.body(), "inputUrl");
            assertFalse(sessionId.isBlank());
            assertTrue(inputUrl.startsWith("/api/files/"));

            HttpResponse<byte[]> inputImgResp = getBytes(client, baseUrl + inputUrl);
            assertEquals(200, inputImgResp.statusCode());
            assertHeaderContains(inputImgResp, "Content-Type", "image/png");
            BufferedImage generatedImg = ImageIO.read(new ByteArrayInputStream(inputImgResp.body()));
            assertNotNull(generatedImg);
            assertEquals(32, generatedImg.getWidth());
            assertEquals(32, generatedImg.getHeight());

            // 4. Studio process + GET output and diff PNGs
            String processBody = String.format(
                    "{\"sessionId\":\"%s\",\"filter\":\"SOBEL\",\"mode\":\"THREADS_BLOCKS\",\"threads\":2,\"tileSize\":16,\"computeDiff\":true}",
                    sessionId);
            HttpResponse<String> procResp = postJson(client, baseUrl + "/api/studio/process", processBody);
            assertEquals(200, procResp.statusCode());
            assertTrue(procResp.body().contains("\"ok\":true"), procResp.body());
            assertTrue(procResp.body().contains("\"diffPixels\":0"), procResp.body());
            assertTrue(procResp.body().contains("\"verified\":true"), procResp.body());

            String outputUrl = extractJsonString(procResp.body(), "outputUrl");
            String diffUrl = extractJsonString(procResp.body(), "diffUrl");

            HttpResponse<byte[]> outImgResp = getBytes(client, baseUrl + outputUrl);
            assertEquals(200, outImgResp.statusCode());
            assertHeaderContains(outImgResp, "Content-Type", "image/png");
            assertNotNull(ImageIO.read(new ByteArrayInputStream(outImgResp.body())));

            HttpResponse<byte[]> diffImgResp = getBytes(client, baseUrl + diffUrl);
            assertEquals(200, diffImgResp.statusCode());
            assertHeaderContains(diffImgResp, "Content-Type", "image/png");
            assertNotNull(ImageIO.read(new ByteArrayInputStream(diffImgResp.body())));

            // 5. Studio upload (real JPEG 12x12 -> converted PNG served via inputUrl)
            byte[] jpegBytes = createJpegBytes(12, 12, 0xFF336699);
            HttpRequest uploadReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/studio/upload"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/octet-stream")
                    .header("X-Filename", "foto teste.jpg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jpegBytes))
                    .build();
            HttpResponse<String> uploadResp = client.send(uploadReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, uploadResp.statusCode());
            assertTrue(uploadResp.body().contains("\"ok\":true"), uploadResp.body());
            String uploadedInputUrl = extractJsonString(uploadResp.body(), "inputUrl");
            assertTrue(uploadedInputUrl.endsWith(".png"), uploadedInputUrl);

            HttpResponse<byte[]> uploadedPngResp = getBytes(client, baseUrl + uploadedInputUrl);
            assertEquals(200, uploadedPngResp.statusCode());
            assertHeaderContains(uploadedPngResp, "Content-Type", "image/png");
            BufferedImage decodedUpload = ImageIO.read(new ByteArrayInputStream(uploadedPngResp.body()));
            assertNotNull(decodedUpload);
            assertEquals(12, decodedUpload.getWidth());
            assertEquals(12, decodedUpload.getHeight());

            // 6. Benchmark run + CSV/TXT artifacts
            HttpResponse<String> benchResp = postJson(client, baseUrl + "/api/benchmark/run",
                    "{\"count\":2,\"width\":32,\"height\":32,\"filter\":\"SOBEL\",\"threads\":2,\"tileSize\":16,\"mpiProcesses\":2}");
            assertEquals(200, benchResp.statusCode());
            String benchBody = benchResp.body();
            assertTrue(benchBody.contains("\"ok\":true"), benchBody);
            assertTrue(benchBody.contains("SEQUENTIAL"), benchBody);
            assertTrue(benchBody.contains("THREADS_ROWS"), benchBody);
            assertTrue(benchBody.contains("THREADS_BLOCKS"), benchBody);
            assertTrue(benchBody.contains("MPJ_MULTICORE"), benchBody);

            String csvUrl = extractJsonString(benchBody, "csvUrl");
            String txtUrl = extractJsonString(benchBody, "txtUrl");

            HttpResponse<String> csvResp = getText(client, baseUrl + csvUrl);
            assertEquals(200, csvResp.statusCode());
            assertTrue(csvResp.body().contains("SEQUENTIAL"), csvResp.body());

            HttpResponse<String> txtResp = getText(client, baseUrl + txtUrl);
            assertEquals(200, txtResp.statusCode());
            assertTrue(txtResp.body().contains("SEQUENTIAL"), txtResp.body());

            // 7. Concurrency run
            HttpResponse<String> concResp = postJson(client, baseUrl + "/api/concurrency/run",
                    "{\"clients\":2,\"count\":1,\"width\":32,\"height\":32,\"filter\":\"BLUR\",\"mode\":\"THREADS_ROWS\",\"threads\":2,\"tileSize\":16,\"mpiProcesses\":2}");
            assertEquals(200, concResp.statusCode());
            assertTrue(concResp.body().contains("\"ok\":true"), concResp.body());
            assertTrue(concResp.body().contains("\"succeeded\":2"), concResp.body());
            assertTrue(concResp.body().contains("\"failed\":0"), concResp.body());

            // 8. Logs
            HttpResponse<String> logsResp = getText(client, baseUrl + "/api/logs");
            assertEquals(200, logsResp.statusCode());
            assertTrue(logsResp.body().contains("\"ok\":true"), logsResp.body());
            assertTrue(logsResp.body().contains("\"logs\":[{"), logsResp.body());
        }
    }

    @Test
    void testSecurityPathTraversalAndPayloadLimit() throws Exception {
        int rmiPort = findFreePort();
        int tcpPort = findFreePortDifferentFrom(rmiPort);

        try (WebStudioServer server = WebStudioServer.start(
                0, HOST, rmiPort, tcpPort, 2, "multicore", false, tempDir);
             HttpClient client = HttpClient.newBuilder()
                     .connectTimeout(Duration.ofSeconds(10))
                     .build()) {

            String baseUrl = server.getBaseUrl();
            assertFalse(server.isCoordinatorRunning());

            // Path traversal and invalid slot checks
            for (String badPath : new String[]{
                    "/api/files/..%2F..%2Fetc/input/passwd",
                    "/api/files/bad..id/input/a.png",
                    "/api/files/sess/invalidslot/a.png"
            }) {
                HttpResponse<String> resp = getText(client, baseUrl + badPath);
                assertTrue(resp.statusCode() == 400 || resp.statusCode() == 404,
                        "Expected 400 or 404 for " + badPath + ", got " + resp.statusCode());
            }

            // Invalid sessionId in studio/process
            HttpResponse<String> badProcResp = postJson(client, baseUrl + "/api/studio/process",
                    "{\"sessionId\":\"../outside\"}");
            assertEquals(400, badProcResp.statusCode());
            assertTrue(badProcResp.body().contains("\"ok\":false"), badProcResp.body());

            // Empty upload body -> 400 JSON
            HttpRequest emptyUploadReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/studio/upload"))
                    .header("X-Filename", "empty.png")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> emptyUploadResp = client.send(
                    emptyUploadReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(400, emptyUploadResp.statusCode());
            assertTrue(emptyUploadResp.body().contains("\"ok\":false"), emptyUploadResp.body());

            // Invalid non-image upload payload -> 400 JSON
            HttpRequest invalidUploadReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/studio/upload"))
                    .header("X-Filename", "fake.png")
                    .POST(HttpRequest.BodyPublishers.ofString("not-a-valid-image-payload", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> invalidUploadResp = client.send(
                    invalidUploadReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(400, invalidUploadResp.statusCode());
            assertTrue(invalidUploadResp.body().contains("\"ok\":false"), invalidUploadResp.body());

            // Invalid ports in /api/config -> 400 JSON
            HttpResponse<String> portZeroResp = postJson(client, baseUrl + "/api/config", "{\"rmiPort\":0}");
            assertEquals(400, portZeroResp.statusCode());
            assertTrue(portZeroResp.body().contains("\"ok\":false"), portZeroResp.body());

            HttpResponse<String> portHighResp = postJson(client, baseUrl + "/api/config", "{\"tcpPort\":70000}");
            assertEquals(400, portHighResp.statusCode());
            assertTrue(portHighResp.body().contains("\"ok\":false"), portHighResp.body());
        }
    }

    private static void assertStaticAsset(
            HttpClient client,
            String baseUrl,
            String urlPath,
            String resourcePath,
            String expectedContentType) throws IOException, InterruptedException {
        HttpResponse<String> resp = getText(client, baseUrl + urlPath);
        assertHeaderEquals(resp, "X-Content-Type-Options", "nosniff");
        if (WebStudioServer.class.getResource(resourcePath) != null) {
            assertEquals(200, resp.statusCode(), "Expected 200 for " + urlPath);
            assertHeaderContains(resp, "Content-Type", expectedContentType);
        } else {
            assertEquals(404, resp.statusCode(), "Expected 404 when resource missing: " + resourcePath);
        }
    }

    private static HttpResponse<String> getText(HttpClient client, String url)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<byte[]> getBytes(HttpClient client, String url)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<String> postJson(HttpClient client, String url, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void assertHeaderEquals(HttpResponse<?> resp, String headerName, String expected) {
        String actual = resp.headers().firstValue(headerName).orElse(null);
        assertEquals(expected, actual, "Unexpected header " + headerName);
    }

    private static void assertHeaderContains(HttpResponse<?> resp, String headerName, String expectedSubstring) {
        String actual = resp.headers().firstValue(headerName).orElse("");
        assertTrue(actual.contains(expectedSubstring),
                "Expected header " + headerName + " to contain '" + expectedSubstring + "', got '" + actual + "'");
    }

    private static String extractJsonString(String json, String fieldName) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        assertTrue(m.find(), "Missing JSON string field '" + fieldName + "' in: " + json);
        return m.group(1).replace("\\/", "/");
    }

    private static byte[] createJpegBytes(int width, int height, int rgb) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpg", baos));
        return baos.toByteArray();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName(HOST))) {
            return ss.getLocalPort();
        }
    }

    private static int findFreePortDifferentFrom(int otherPort) throws IOException {
        int port = findFreePort();
        while (port == otherPort) {
            port = findFreePort();
        }
        return port;
    }
}
