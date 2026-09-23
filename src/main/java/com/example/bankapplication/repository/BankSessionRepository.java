package com.example.bankapplication.repository;

import com.example.bankapplication.entity.BankSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface BankSessionRepository extends JpaRepository<BankSession, Long> {
    Optional<BankSession> findByTokenHash(String tokenHash);
    long deleteByTokenHash(String tokenHash);
    // housekeeping: forget a customer's sessions that have already expired
    long deleteByPhnoAndExpiresAtBefore(long phno, Instant cutoff);
}
