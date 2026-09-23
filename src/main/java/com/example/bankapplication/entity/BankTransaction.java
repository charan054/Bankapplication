package com.example.bankapplication.entity;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="BankTransactions")
@Data
@JsonPropertyOrder({
        "id",
        "transactionId",
        "userId",
        "phno",
        "action",
        "amount",
        "Balance",
        "createdAt"
})
public class BankTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(unique = true)
    private long transactionId;
    @Column(name = "user_id")
    private long userId;
    private long phno;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    private String action;
    @Column(precision = 19, scale = 2)
    private BigDecimal balance;
    // Nullable: rows written before this field existed have none. LegacyTransactionTimestampBackfill fills them
    // in once, on startup, since there is no real record of when they actually happened.
    private Instant createdAt;
}
