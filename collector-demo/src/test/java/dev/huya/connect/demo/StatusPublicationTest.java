package dev.huya.connect.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class StatusPublicationTest {
    @TempDir Path root;

    private Process holdWithoutDeleteSharing(Path file) throws Exception {
        Path script = root.resolve("hold.ps1");
        Files.writeString(script, "param($Path)\n$s=[IO.File]::Open($Path,'Open','Read','ReadWrite')\n"
                + "try { [Console]::WriteLine('locked'); [Console]::ReadLine() | Out-Null } finally { $s.Dispose() }");
        Process process = new ProcessBuilder("pwsh.exe", "-NoProfile", "-NonInteractive", "-File",
                script.toString(), file.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertEquals("locked", process.inputReader().readLine());
        return process;
    }

    private void release(Process process) throws Exception {
        process.outputWriter().write("release\n"); process.outputWriter().flush();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
    }

    @Test void aShortReaderLockIsRetried() throws Exception {
        Path file = root.resolve("status.json");
        CollectorDemo.atomicJson(file, Map.of("version", 1));
        Process reader = holdWithoutDeleteSharing(file);
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(200); release(reader); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            releaser.start();
            CollectorDemo.atomicJson(file, Map.of("version", 2));
            assertEquals(2, new ObjectMapper().readTree(file.toFile()).get("version").asInt());
        } finally { releaser.join(5000); reader.destroyForcibly(); }
    }

    @Test void aLongStatusLockDoesNotCloseCaptureAndRecoveryIsReported() throws Exception {
        try (CollectorDemo demo = new CollectorDemo(root)) {
            demo.publish(false);
            Path file = root.resolve("status.json");
            byte[] previous = Files.readAllBytes(file);
            Process reader = holdWithoutDeleteSharing(file);
            try {
                assertDoesNotThrow(() -> demo.publish(false));
                assertArrayEquals(previous, Files.readAllBytes(file));
            } finally { release(reader); }
            demo.publish(false);
            var state = new ObjectMapper().readTree(file.toFile());
            assertTrue(state.get("statusWriteFailures").asLong() > 0);
            assertNull(state.get("fatalError").textValue());
            assertNotEquals("stopped", state.get("phase").asText());
        }
    }
}
