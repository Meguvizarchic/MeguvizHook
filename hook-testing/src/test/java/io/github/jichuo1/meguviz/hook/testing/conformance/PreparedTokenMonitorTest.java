package io.github.jichuo1.meguviz.hook.testing.conformance;

import io.github.jichuo1.meguviz.hook.api.HookSpec;
import io.github.jichuo1.meguviz.hook.core.DispatchChannel;
import io.github.jichuo1.meguviz.hook.core.HookEnvironment;
import io.github.jichuo1.meguviz.hook.testing.FakeBackend;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Child JVM keeps any regression-induced monitor cycle bounded and disposable. */
public final class PreparedTokenMonitorTest {
    @Test public void externalMonitorClose() throws Exception {
        runFixture(PreparedTokenCloseFixture.class, "PRIVATE_TOKEN_MONITOR_SMOKE_OK");
    }

    @Test public void externalMonitorCommit() throws Exception {
        runFixture(PreparedTokenCommitFixture.class, "PRIVATE_TOKEN_COMMIT_SMOKE_OK");
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
    }

    private static void runFixture(Class<?> fixture, String expected) throws Exception {
        String cp = System.getProperty("java.class.path") + File.pathSeparator + location(fixture)
                + File.pathSeparator + location(HookEnvironment.class) + File.pathSeparator + location(DispatchChannel.class)
                + File.pathSeparator + location(HookSpec.class) + File.pathSeparator + location(FakeBackend.class);
        String javaExecutable = new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").getAbsolutePath();
        Process process = new ProcessBuilder(javaExecutable, "-cp", cp, fixture.getName()).redirectErrorStream(true).start();
        try {
            assertTrue("bounded child JVM timed out", process.waitFor(25, TimeUnit.SECONDS));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) != -1;) bytes.write(buffer, 0, n);
            }
            String output = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
            assertTrue(output, output.contains(expected));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
