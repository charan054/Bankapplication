package com.example.bankapplication.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class BankKafkaConsumer {

    @KafkaListener(
            topics = KafkaTopics.BANK_NOTIFICATION_TOPIC,
            groupId = "bank-notification-group"
    )
    public void consume(String message) {
        System.out.println("Kafka message received: " + message);
    }
}