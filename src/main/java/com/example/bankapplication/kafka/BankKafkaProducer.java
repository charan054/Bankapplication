package com.example.bankapplication.kafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class BankKafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public BankKafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendMessage(String message) {
        kafkaTemplate.send("bank-notification-topic", message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        System.out.println("Kafka send failed: " + ex.getMessage());
                    } else {
                        System.out.println("Kafka message sent successfully "+message);
                    }
                });
    }
}
