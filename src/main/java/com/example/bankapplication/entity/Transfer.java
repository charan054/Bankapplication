package com.example.bankapplication.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A completed transfer between two accounts, keyed by the caller's idempotency key. If a request with the same
 * key arrives again (a retry after a timeout, for example), BankService.transfer finds this row and returns the
 * same result instead of moving the money a second time.
 */
@Entity
@Table(name = "transfer")
@Data
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true)
    private String idempotencyKey;
    private long payerPhno;
    private long receiverPhno;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    private long debitTransactionId;
    private long creditTransactionId;
    private Instant createdAt;
}
