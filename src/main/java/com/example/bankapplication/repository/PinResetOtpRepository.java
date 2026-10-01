package com.example.bankapplication.repository;

import com.example.bankapplication.entity.PinResetOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface PinResetOtpRepository extends JpaRepository<PinResetOtp, Long> {
    Optional<PinResetOtp> findByPhno(long phno);
    // a fresh request for the same phone number must invalidate any code still outstanding from an earlier one
    long deleteByPhno(long phno);
    // housekeeping: forget every expired code, not just one customer's - run periodically (see PinResetService)
    long deleteByExpiresAtBefore(Instant cutoff);
}
