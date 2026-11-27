package estudio.net;

import estudio.model.BenchmarkReport;
import estudio.model.JobStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TcpProtocol {
    public static final byte FRAME_PROGRESS = 0x01;
    public static final byte FRAME_REPORT = 0x02;
    public static final byte FRAME_IMAGE = 0x03;
    public static final byte FRAME_DONE = 0x04;
    public static final byte FRAME_ERROR = (byte) 0xFF;

    public static final long MAX_FILE_SIZE_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_REPORT_STRING_BYTES = 32 * 1024 * 1024;
    private static final int CHUNK_SIZE = 65_536;

    public record UploadBatch(String jobId, List<Path> files) {
    }

    public record ProgressFrame(JobStatus.State state, int completedSteps, int totalSteps, String message) {
    }

    public record ReportFrame(String tableText, String csvText, long diffPixels, boolean verified) {
    }

    private TcpProtocol() {
    }

    public static void sendUploadBatch(DataOutputStream out, String jobId, List<Path> files) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        if (Objects.requireNonNull(jobId, "jobId must not be null").isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        Objects.requireNonNull(files, "files must not be null");

        out.writeUTF(jobId);
        out.writeInt(files.size());
        byte[] buffer = new byte[CHUNK_SIZE];
        for (Path file : files) {
            Path normalized = Objects.requireNonNull(file, "file element must not be null").toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("Upload file does not exist: " + normalized);
            }
            writeFilePayload(out, normalized, buffer);
        }
        out.flush();
    }

    public static UploadBatch readUploadBatch(DataInputStream in, Path inputDir) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(inputDir, "inputDir must not be null");

        String jobId = in.readUTF();
        if (jobId.isBlank()) {
            throw new IOException("Received blank jobId in upload batch");
        }
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("Negative file count in upload batch: " + count);
        }

        Path dirAbs = inputDir.toAbsolutePath().normalize();
        Files.createDirectories(dirAbs);

        List<Path> received = new ArrayList<>(count);
        byte[] buffer = new byte[CHUNK_SIZE];
        for (int i = 0; i < count; i++) {
            received.add(readFilePayload(in, dirAbs, buffer));
        }
        return new UploadBatch(jobId, List.copyOf(received));
    }

    public static void sendProgress(
            DataOutputStream out,
            JobStatus.State state,
            int completedSteps,
            int totalSteps,
            String message) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(state, "state must not be null");

        out.writeByte(FRAME_PROGRESS);
        out.writeUTF(state.name());
        out.writeInt(Math.max(0, completedSteps));
        out.writeInt(Math.max(0, totalSteps));
        out.writeUTF(message == null ? "" : message);
        out.flush();
    }

    public static ProgressFrame readProgressPayload(DataInputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        JobStatus.State state = JobStatus.State.valueOf(in.readUTF());
        int completedSteps = in.readInt();
        int totalSteps = in.readInt();
        String message = in.readUTF();
        return new ProgressFrame(state, completedSteps, totalSteps, message);
    }

    public static void sendReport(DataOutputStream out, BenchmarkReport report) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(report, "report must not be null");

        long totalDiffs = 0L;
        boolean allVerified = !report.getEntries().isEmpty();
        for (BenchmarkReport.Entry entry : report.getEntries()) {
            totalDiffs += entry.getDiffPixels();
            if (!entry.isVerified()) {
                allVerified = false;
            }
        }

        out.writeByte(FRAME_REPORT);
        writeLargeUtf(out, report.formatTable());
        writeLargeUtf(out, report.toCsv());
        out.writeLong(totalDiffs);
        out.writeBoolean(allVerified);
        out.flush();
    }

    public static ReportFrame readReportPayload(DataInputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        String tableText = readLargeUtf(in);
        String csvText = readLargeUtf(in);
        long diffPixels = in.readLong();
        boolean verified = in.readBoolean();
        return new ReportFrame(tableText, csvText, diffPixels, verified);
    }

    private static void writeLargeUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_STRING_BYTES) {
            throw new IOException("Report string exceeds maximum allowed size: " + bytes.length);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readLargeUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_REPORT_STRING_BYTES) {
            throw new IOException("Invalid report string length: " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void sendImage(DataOutputStream out, Path imageFile) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        Path normalized = Objects.requireNonNull(imageFile, "imageFile must not be null")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Image file does not exist: " + normalized);
        }

        out.writeByte(FRAME_IMAGE);
        writeFilePayload(out, normalized, new byte[CHUNK_SIZE]);
        out.flush();
    }

    public static Path readImageToDir(DataInputStream in, Path outputDir) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        Path dirAbs = Objects.requireNonNull(outputDir, "outputDir must not be null")
                .toAbsolutePath().normalize();
        Files.createDirectories(dirAbs);
        return readFilePayload(in, dirAbs, new byte[CHUNK_SIZE]);
    }

    public static void sendDone(DataOutputStream out, String message) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        out.writeByte(FRAME_DONE);
        out.writeUTF(message == null ? "" : message);
        out.flush();
    }

    public static String readDonePayload(DataInputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        return in.readUTF();
    }

    public static void sendError(DataOutputStream out, String message) throws IOException {
        Objects.requireNonNull(out, "out must not be null");
        out.writeByte(FRAME_ERROR);
        out.writeUTF(message == null ? "Unknown error" : message);
        out.flush();
    }

    public static String readErrorPayload(DataInputStream in) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        return in.readUTF();
    }

    private static void writeFilePayload(DataOutputStream out, Path file, byte[] buffer) throws IOException {
        long size = Files.size(file);
        out.writeUTF(file.getFileName().toString());
        out.writeLong(size);

        long remaining = size;
        try (InputStream fileIn = Files.newInputStream(file)) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = fileIn.read(buffer, 0, toRead);
                if (read < 0) {
                    throw new EOFException("Unexpected EOF reading local file: " + file);
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static Path readFilePayload(DataInputStream in, Path dirAbs, byte[] buffer) throws IOException {
        String rawName = in.readUTF();
        long size = in.readLong();
        if (size < 0) {
            throw new IOException("Negative file size for " + rawName + ": " + size);
        }
        if (size > MAX_FILE_SIZE_BYTES) {
            throw new IOException("File size exceeds maximum allowed limit (" + MAX_FILE_SIZE_BYTES + " bytes) for "
                    + rawName + ": " + size);
        }

        Path target = resolveSafeFile(dirAbs, rawName);
        long remaining = size;
        try (OutputStream fileOut = Files.newOutputStream(target)) {
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, toRead);
                if (read < 0) {
                    throw new EOFException("Unexpected EOF receiving file: " + rawName);
                }
                fileOut.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return target;
    }

    private static Path resolveSafeFile(Path dirAbs, String rawName) throws IOException {
        if (rawName == null || rawName.isBlank()) {
            throw new IOException("Empty file name in TCP payload");
        }
        Path fileNamePath = Path.of(rawName).getFileName();
        if (fileNamePath == null) {
            throw new IOException("Invalid file name in TCP payload: " + rawName);
        }
        String safeName = fileNamePath.toString();
        if (safeName.isBlank() || ".".equals(safeName) || "..".equals(safeName) || !safeName.equals(rawName)) {
            throw new IOException("Unsafe file name rejected (path traversal): " + rawName);
        }
        Path resolved = dirAbs.resolve(safeName).normalize();
        if (!resolved.startsWith(dirAbs) || !dirAbs.equals(resolved.getParent())) {
            throw new IOException("Resolved path escapes target directory: " + rawName);
        }
        return resolved;
    }
}
