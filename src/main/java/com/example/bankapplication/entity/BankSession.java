package com.example.bankapplication.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One customer login. Only a SHA-256 hash of the token is stored, so a copy of this table cannot be used to
 * act as a customer (mirrors PhonepayService's UserSession).
 */
@Data
@Entity
@Table(name = "bank_session")
public class BankSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    private long phno;
    private Instant createdAt;
    private Instant expiresAt;
}
