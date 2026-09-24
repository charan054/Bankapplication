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
    // every session belonging to a phone number, regardless of expiry - used when that number stops being
    // authoritative for whoever's holding a token for it (account deleted, or the number changed)
    long deleteByPhno(long phno);
    // housekeeping: forget EVERY expired session, not just one customer's - run periodically (see SessionService)
    // so a session nobody ever logs into again isn't kept forever.
    long deleteByExpiresAtBefore(Instant cutoff);
}
