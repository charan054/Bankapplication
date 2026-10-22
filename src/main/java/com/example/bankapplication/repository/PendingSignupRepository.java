package com.example.bankapplication.repository;

import com.example.bankapplication.entity.PendingSignup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PendingSignupRepository extends JpaRepository<PendingSignup, Long> {
    Optional<PendingSignup> findByPhno(long phno);
    long deleteByPhno(long phno);
    long deleteByExpiresAtBefore(Instant cutoff);
}
