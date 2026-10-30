package com.example.bankapplication.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money a customer has locked away from their savings balance for a fixed term at a rate agreed up front. The
 * interest is simple (principal x rate x term) and is paid with the principal when the term ends; breaking it early
 * returns the principal only. See FixedDepositService.
 */
@Data
@Entity
@Table(name = "fixed_deposit", indexes = @Index(name = "idx_fixed_deposit_phno", columnList = "phno"))
public class FixedDeposit {
    public static final String ACTIVE = "ACTIVE";
    public static final String MATURED = "MATURED";
    public static final String BROKEN = "BROKEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private long phno;
    @Column(precision = 19, scale = 2)
    private BigDecimal principal;
    // Annual rate in percent, fixed for the whole term at the moment the deposit is opened.
    @Column(precision = 5, scale = 2)
    private BigDecimal ratePercent;
    private int termMonths;
    private Instant openedAt;
    private Instant maturesAt;
    @Column(length = 10, nullable = false)
    private String status = ACTIVE;
    // What went back to the savings balance when it closed (principal + interest, or principal alone if broken).
    @Column(precision = 19, scale = 2)
    private BigDecimal payout;
    private Instant closedAt;
    // A maturity credit racing a break (or two list calls racing each other) must not pay out twice.
    @Version
    private long version;
}
