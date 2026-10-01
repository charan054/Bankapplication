package com.example.bankapplication.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One outstanding forgot-PIN code. Only a SHA-256 hash of the 6-digit code is stored, same reasoning as
 * BankSession's tokenHash - a copy of this table cannot be used to reset anyone's PIN.
 */
@Data
@Entity
@Table(name = "pin_reset_otp")
public class PinResetOtp {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column(nullable = false, unique = true, length = 64)
    private String otpHash;
    private long phno;
    private Instant createdAt;
    private Instant expiresAt;
    // Wrong-code guesses against this one OTP; reset() gives up once this crosses MAX_VERIFY_ATTEMPTS so a
    // 6-digit code can't just be brute-forced within its expiry window.
    private int attempts;
}
