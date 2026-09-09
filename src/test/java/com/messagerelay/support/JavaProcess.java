package com.messagerelay.support;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class JavaProcess implements AutoCloseable {

    private final Process process;
    private final BufferedWriter input;
    private final Thread outputReader;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final StringBuffer transcript = new StringBuffer();

    public JavaProcess(Class<?> mainClass, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(mainClass.getName());
        command.addAll(List.of(arguments));

        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        outputReader = Thread.ofVirtual().start(() -> {
            try (BufferedReader output = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = output.readLine()) != null) {
                    transcript.append(line).append('\n');
                    lines.add(line);
                }
            } catch (IOException failure) {
                transcript.append(failure).append('\n');
            }
        });
    }

    public void sendLine(String line) throws IOException {
        input.write(line);
        input.newLine();
        input.flush();
    }

    public void endInput() throws IOException {
        input.close();
    }

    public String awaitOutput(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line != null && line.contains(expected)) {
                return line;
            }
        }
        throw new AssertionError("Expected output: " + expected + "\nActual output:\n" + transcript);
    }

    public void awaitSuccessfulExit() throws InterruptedException {
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Process did not exit:\n" + transcript);
        outputReader.join(1_000);
        assertFalse(outputReader.isAlive(), "Process output reader did not finish");
        assertEquals(0, process.exitValue(), transcript.toString());
    }

    @Override
    public void close() throws InterruptedException {
        try {
            input.close();
        } catch (IOException failure) {
            transcript.append("Could not close process input: ").append(failure).append('\n');
        }
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "Could not terminate test process");
        }
        outputReader.join(1_000);
    }
}
