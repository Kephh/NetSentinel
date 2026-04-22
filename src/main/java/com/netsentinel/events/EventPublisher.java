package com.netsentinel.events;

public interface EventPublisher extends AutoCloseable {
    void publish(TrafficEvent event);

    @Override
    default void close() {
    }
}
