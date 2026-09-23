package com.example.bankapplication.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name="Bank")
@Data
@JsonPropertyOrder({
        "userId",
        "acno",
        "firstName",
        "lastName",
        "aadharnumber",
        "phno",
        "balance"
})
public class Bank {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int userId;
    @Column(unique = true)
    private long acno;
    private String firstName;
    private String lastName;
    @Column(unique = true)
    private long aadharNumber;
    @Column(unique = true)
    private long phno;
    private double balance;
    // BCrypt hash of the customer's PIN. Null on accounts created before login existed, or reset by an admin;
    // such an account cannot log in until an admin sets a PIN via PUT /bank/admin/set-pin. Never sent in any response.
    @JsonIgnore
    private String pinHash;
    // A simple lockout so a 4-6 digit PIN cannot just be brute-forced: counted on each wrong PIN, reset on success.
    @JsonIgnore
    private int failedLoginAttempts;
    @JsonIgnore
    private Instant lockedUntil;
    // Optimistic lock: Hibernate bumps this on every update and refuses an update made from a stale copy.
    // Internal only, so it is kept out of the JSON.
    @Version
    @JsonIgnore
    private long version;
    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", referencedColumnName = "userId")
    private List<BankTransaction>  transactions;
}
