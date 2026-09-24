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
@Table(name = "transfer", uniqueConstraints = @UniqueConstraint(name = "uq_transfer_payer_idempotency_key",
        columnNames = {"payer_phno", "idempotency_key"}))
@Data
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // Uniqueness is scoped to the payer (see the table's unique constraint), not global - so two different
    // customers' otherwise-unrelated transfers can never collide just because their own key-generation schemes
    // happened to produce the same string.
    @Column(nullable = false)
    private String idempotencyKey;
    private long payerPhno;
    private long receiverPhno;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    private long debitTransactionId;
    private long creditTransactionId;
    private Instant createdAt;
}
