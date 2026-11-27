package estudio;

import estudio.model.BenchmarkReport;
import estudio.model.FilterType;
import estudio.net.TcpProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpProtocolBugReproductionTest {

    @Test
    void sendReportHandlesTableAndCsvLargerThan70KB() throws IOException {
        List<BenchmarkReport.Entry> entries = new ArrayList<>();
        String longNote = "N".repeat(200);
        for (int i = 0; i < 1_200; i++) {
            entries.add(new BenchmarkReport.Entry(
                    "ENGINE_" + i,
                    4,
                    10L + i,
                    20L + i,
                    1.5,
                    75.0,
                    0L,
                    true,
                    longNote + "_" + i));
        }

        BenchmarkReport report = new BenchmarkReport(FilterType.BLUR, 10, 64, 64, entries);
        String expectedTable = report.formatTable();
        String expectedCsv = report.toCsv();
        assertTrue(expectedTable.getBytes(StandardCharsets.UTF_8).length > 70_000,
                "Test setup requires tableText > 70,000 UTF-8 bytes");
        assertTrue(expectedCsv.getBytes(StandardCharsets.UTF_8).length > 70_000,
                "Test setup requires csvText > 70,000 UTF-8 bytes");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            TcpProtocol.sendReport(out, report);
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            byte frameType = in.readByte();
            assertEquals(TcpProtocol.FRAME_REPORT, frameType);
            TcpProtocol.ReportFrame frame = TcpProtocol.readReportPayload(in);
            assertEquals(expectedTable, frame.tableText());
            assertEquals(expectedCsv, frame.csvText());
            assertEquals(0L, frame.diffPixels());
            assertTrue(frame.verified());
        }
    }

    @Test
    void readFilePayloadRejectsOversizedHeaderBeforeReadingBytes(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("oversized.png");
            out.writeLong(10L * 1024L * 1024L * 1024L); // 10 GiB declared, 0 bytes following
            out.flush();
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            IOException thrown = assertThrows(
                    IOException.class,
                    () -> TcpProtocol.readImageToDir(in, tempDir),
                    "readFilePayload must reject declared file sizes above the maximum allowed limit");
            assertFalse(
                    thrown instanceof EOFException,
                    "Expected size-limit rejection before attempting to read payload bytes, but got EOFException: "
                            + thrown.getMessage());
        }
    }
}
