package com.netsentinel.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

public final class KafkaJsonEventPublisher implements EventPublisher {
    private static final Logger logger = LoggerFactory.getLogger(KafkaJsonEventPublisher.class);

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Producer<String, String> producer;
    private final String topic;

    public KafkaJsonEventPublisher(String bootstrapServers, String topic) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("audit.kafkaBootstrapServers is required when audit.sink is kafka");
        }
        this.topic = Objects.requireNonNullElse(topic, "netsentinel-traffic");
        this.producer = new KafkaProducer<>(properties(bootstrapServers));
    }

    @Override
    public void publish(TrafficEvent event) {
        try {
            String payload = mapper.writeValueAsString(event);
            String key = event.routeId() + ":" + event.backendId();
            producer.send(new ProducerRecord<>(topic, key, payload), (metadata, exception) -> {
                if (exception != null) {
                    logger.warn("Failed to publish traffic event to Kafka: {}", exception.getMessage());
                }
            });
        } catch (Exception exception) {
            logger.warn("Failed to serialize Kafka traffic event: {}", exception.getMessage());
        }
    }

    @Override
    public void close() {
        producer.flush();
        producer.close(Duration.ofSeconds(2));
    }

    private static Properties properties(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000");
        return props;
    }
}
