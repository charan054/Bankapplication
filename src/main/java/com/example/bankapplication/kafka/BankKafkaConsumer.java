package com.example.bankapplication.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class BankKafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(BankKafkaConsumer.class);

    @KafkaListener(
            topics = KafkaTopics.BANK_NOTIFICATION_TOPIC,
            groupId = "bank-notification-group"
    )
    public void consume(String message) {
        log.info("Kafka message received: {}", message);
    }
}