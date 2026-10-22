package com.example.bankapplication.repository;

import com.example.bankapplication.entity.WithdrawalOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface WithdrawalOtpRepository extends JpaRepository<WithdrawalOtp, Long> {
    Optional<WithdrawalOtp> findByPhno(long phno);
    long deleteByPhno(long phno);
    long deleteByExpiresAtBefore(Instant cutoff);
}
