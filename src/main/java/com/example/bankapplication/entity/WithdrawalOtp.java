package com.example.bankapplication.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An emailed confirmation code for ONE large withdrawal. Bound to the exact amount it was requested for, so a code
 * obtained for a small amount can never release a bigger one. Only a SHA-256 hash of the 6-digit code is stored.
 */
@Data
@Entity
@Table(name = "withdrawal_otp")
public class WithdrawalOtp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // One outstanding code per account: asking again replaces the earlier one.
    @Column(nullable = false, unique = true)
    private long phno;
    @Column(nullable = false, length = 64)
    private String otpHash;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    private Instant createdAt;
    private Instant expiresAt;
    private int attempts;
}
