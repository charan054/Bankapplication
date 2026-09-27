package com.example.bankapplication.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();

        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // KafkaProducer.send() blocks the calling thread (up to this long) while it fetches topic metadata, even
        // though the CompletableFuture it returns looks "fire and forget" - the default 60s default meant a
        // withdraw/deposit call would hang the whole HTTP request for a minute whenever no broker is reachable,
        // long past any caller's own timeout (e.g. PhonepayService's 5s Feign read-timeout on this service),
        // which surfaced as a spurious "no answer from the bank" instead of the notification just failing fast.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}