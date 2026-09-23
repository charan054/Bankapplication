package com.example.bankapplication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BankapplicationApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankapplicationApplication.class, args);
    }

}
