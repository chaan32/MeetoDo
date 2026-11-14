package com.meetodo.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RagEvaluationScriptTests {
    @Test
    void pythonEvaluatorContractsRunWithoutServerOrNetwork() throws Exception {
        // Drain to disk so verbose failures cannot fill the child process output pipe.
        Path output = Files.createTempFile("meetodo-rag-eval-tests-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder("python3", "-B", "-m", "unittest", "discover",
                    "-s", "scripts/tests", "-p", "test_evaluate_rag.py", "-v")
                    .directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Python evaluator tests timed out").isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(output)).isZero();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
