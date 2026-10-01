package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.PinResetOtp;
import com.example.bankapplication.exception.InvalidOtpException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.PinResetOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Forgot-PIN by email. Mirrors SessionService's shape (random secret, stored only as a hash, scheduled
 * cleanup) but for a short numeric code emailed to the account's address instead of a bearer token.
 */
@Service
public class PinResetService {
    private static final Logger log = LoggerFactory.getLogger(PinResetService.class);
    static final int MAX_VERIFY_ATTEMPTS = 5;

    private final PinResetOtpRepository otps;
    private final BankRepository users;
    private final BankService bankService;
    private final MailService mailService;
    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom random = new SecureRandom();

    public PinResetService(PinResetOtpRepository otps, BankRepository users, BankService bankService,
                           MailService mailService, Clock clock,
                           @Value("${bank.pin-reset.ttl-minutes:10}") long ttlMinutes) {
        this.otps = otps;
        this.users = users;
        this.bankService = bankService;
        this.mailService = mailService;
        this.clock = clock;
        this.timeToLive = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Deliberately silent either way (no exception, no signal of what happened) when the phone number has no
     * account, or that account has no email on file - same phone-number-enumeration reasoning as
     * BankService.login. The caller always sees the same "check your email" response regardless.
     */
    @Transactional
    public void requestReset(long phno) {
        Bank user = users.findByphno(phno);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }

        Instant now = clock.instant();
        otps.deleteByPhno(phno);

        String code = String.format("%06d", random.nextInt(1_000_000));

        PinResetOtp otp = new PinResetOtp();
        otp.setOtpHash(hash(code));
        otp.setPhno(phno);
        otp.setCreatedAt(now);
        otp.setExpiresAt(now.plus(timeToLive));
        otp.setAttempts(0);
        otps.save(otp);

        mailService.send(user.getEmail(), "Your PIN reset code",
                "Your PIN reset code is " + code + ". It expires in " + timeToLive.toMinutes()
                        + " minutes. If you didn't request this, you can ignore this email.");
    }

    @Transactional
    public void resetPin(long phno, String code, String newPin) {
        PinResetOtp otp = otps.findByPhno(phno).orElseThrow(
                () -> new InvalidOtpException("Invalid or expired code."));

        if (!otp.getExpiresAt().isAfter(clock.instant())) {
            otps.delete(otp);
            throw new InvalidOtpException("Invalid or expired code.");
        }
        if (otp.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            otps.delete(otp);
            throw new InvalidOtpException("Too many incorrect attempts. Request a new code.");
        }
        if (!otp.getOtpHash().equals(hash(code))) {
            otp.setAttempts(otp.getAttempts() + 1);
            otps.save(otp);
            throw new InvalidOtpException("Invalid or expired code.");
        }

        otps.delete(otp);
        // reuses the admin reset path: re-hashes the PIN, clears any lockout, and invalidates existing sessions
        bankService.setPin(phno, newPin);
    }

    // requestReset() only cleans up the one phone number it's already touching. A code nobody ever comes back
    // to redeem would otherwise sit in the table forever.
    @Scheduled(fixedRateString = "${bank.pin-reset.cleanup-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpiredCodes() {
        long removed = otps.deleteByExpiresAtBefore(clock.instant());
        if (removed > 0) {
            log.info("Purged {} expired pin-reset code(s)", removed);
        }
    }

    private String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
