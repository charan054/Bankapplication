package com.example.bankapplication.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name="otpdata")
public class OtpData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private long phno;
    private LocalDateTime expirytime;
    private boolean verified;
}
