package estudio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MpjFakeStarterMain {
    private MpjFakeStarterMain() {
    }

    public static void main(String[] args) throws Exception {
        String devMode = "";
        Path outputDir = null;

        for (int i = 0; i < args.length; i++) {
            if ("-dev".equals(args[i]) && i + 1 < args.length) {
                devMode = args[++i];
            } else if (args[i].startsWith("--outputDir=")) {
                outputDir = Path.of(args[i].substring("--outputDir=".length()));
            }
        }

        if (outputDir != null) {
            Files.createDirectories(outputDir);
            Files.writeString(
                    outputDir.resolve("pid.txt"),
                    Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
        }

        switch (devMode) {
            case "hang" -> {
                System.out.println("FAKE_MPJ_READY");
                System.out.flush();
                Thread.sleep(10_000L);
            }
            case "spam" -> {
                String line = "X".repeat(100);
                for (int i = 0; i < 2_500; i++) {
                    System.out.println("LINE_" + i + "_" + line);
                }
                System.out.flush();
                System.exit(1);
            }
            default -> {
                System.err.println("Unknown fake devMode: " + devMode);
                System.exit(2);
            }
        }
    }
}
