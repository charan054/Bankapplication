package com.example.bankapplication.kafka;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class BankKafkaProducer {
    private static final Logger log = LoggerFactory.getLogger(BankKafkaProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    public BankKafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendMessage(String message) {
        kafkaTemplate.send("bank-notification-topic", message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka send failed: {}", ex.getMessage());
                    } else {
                        log.info("Kafka message sent successfully {}", message);
                    }
                });
    }
}
