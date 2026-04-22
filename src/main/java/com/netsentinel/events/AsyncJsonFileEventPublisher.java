package com.netsentinel.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AsyncJsonFileEventPublisher implements EventPublisher {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object writerLock = new Object();
    private final BufferedWriter writer;

    public AsyncJsonFileEventPublisher(Path filePath) throws IOException {
        Path absolute = filePath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.writer = Files.newBufferedWriter(
                absolute,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    @Override
    public void publish(TrafficEvent event) {
        executor.submit(() -> {
            try {
                String payload = mapper.writeValueAsString(event);
                synchronized (writerLock) {
                    writer.write(payload);
                    writer.newLine();
                    writer.flush();
                }
            } catch (Exception exception) {
                System.err.println("Failed to write traffic event to file: " + exception.getMessage());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (writerLock) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
    }
}
