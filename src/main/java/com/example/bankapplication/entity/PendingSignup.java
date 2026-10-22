package com.example.bankapplication.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A self-signup waiting for its emailed code. No account exists yet: it is created only once the code is entered.
 * Only a SHA-256 hash of the 6-digit code is stored (same reasoning as PinResetOtp) and the PIN is already BCrypt-hashed.
 */
@Data
@Entity
@Table(name = "pending_signup")
public class PendingSignup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    // One outstanding signup per phone number: a new request replaces the earlier one.
    @Column(nullable = false, unique = true)
    private long phno;
    @Column(nullable = false, length = 64)
    private String otpHash;
    private String firstName;
    private String lastName;
    private long aadharNumber;
    private String email;
    private String pinHash;
    private Instant createdAt;
    private Instant expiresAt;
    // Wrong-code guesses; the signup is discarded after MAX_VERIFY_ATTEMPTS.
    private int attempts;
}
