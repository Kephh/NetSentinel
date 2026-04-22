package com.netsentinel.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AsyncJsonLogEventPublisher implements EventPublisher {
    private static final Logger logger = LoggerFactory.getLogger(AsyncJsonLogEventPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final PrintStream output;

    public AsyncJsonLogEventPublisher(PrintStream output) {
        this.output = output;
    }

    @Override
    public void publish(TrafficEvent event) {
        executor.submit(() -> {
            try {
                output.println(mapper.writeValueAsString(event));
            } catch (Exception exception) {
                logger.error("Failed to serialize traffic event", exception);
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
