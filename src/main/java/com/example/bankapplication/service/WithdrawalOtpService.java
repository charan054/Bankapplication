package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.WithdrawalOtp;
import com.example.bankapplication.exception.InvalidWithdrawalCodeException;
import com.example.bankapplication.exception.OtpRequiredException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.WithdrawalOtpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 * Emailed confirmation for large self-service withdrawals (the customer's own token calling PUT /bank/withdraw).
 * Withdrawals below bank.withdraw-otp.threshold are unchanged; 0 turns the feature off. Payments that PhonepayService
 * makes with the service key do not go through this - the buyer already authorised those with their PhonePe PIN.
 */
@Service
public class WithdrawalOtpService {
    private static final Logger log = LoggerFactory.getLogger(WithdrawalOtpService.class);
    static final int MAX_VERIFY_ATTEMPTS = 5;

    private final WithdrawalOtpRepository otps;
    private final BankRepository users;
    private final MailService mailService;
    private final Clock clock;
    private final BigDecimal threshold;
    private final Duration timeToLive;
    private final boolean logCodeWhenMailFails;
    private final SecureRandom random = new SecureRandom();

    public WithdrawalOtpService(WithdrawalOtpRepository otps, BankRepository users, MailService mailService, Clock clock,
                                @Value("${bank.withdraw-otp.threshold:10000}") BigDecimal threshold,
                                @Value("${bank.withdraw-otp.ttl-minutes:5}") long ttlMinutes,
                                @Value("${bank.withdraw-otp.log-codes-when-mail-fails:false}") boolean logCodeWhenMailFails) {
        this.otps = otps;
        this.users = users;
        this.mailService = mailService;
        this.clock = clock;
        this.threshold = threshold;
        this.timeToLive = Duration.ofMinutes(ttlMinutes);
        this.logCodeWhenMailFails = logCodeWhenMailFails;
    }

    public boolean isRequiredFor(BigDecimal amount) {
        return threshold.signum() > 0 && amount.compareTo(threshold) >= 0;
    }

    /** Emails a code for exactly this amount. */
    @Transactional
    public void request(long phno, BigDecimal amount) {
        if (!isRequiredFor(amount)) {
            throw new WithdrawException("No confirmation code is needed for this amount.");
        }
        Bank user = users.findByphno(phno);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new WithdrawException("Add an email address to your account to withdraw INR " + threshold.toPlainString()
                    + " or more - the confirmation code is sent there.");
        }

        Instant now = clock.instant();
        otps.deleteByPhno(phno);
        otps.flush();

        String code = String.format("%06d", random.nextInt(1_000_000));
        WithdrawalOtp otp = new WithdrawalOtp();
        otp.setPhno(phno);
        otp.setOtpHash(hash(code));
        otp.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        otp.setCreatedAt(now);
        otp.setExpiresAt(now.plus(timeToLive));
        otp.setAttempts(0);
        otps.save(otp);

        boolean sent = mailService.send(user.getEmail(), "Confirm your withdrawal",
                "Your code to confirm a withdrawal of INR " + otp.getAmount().toPlainString() + " is " + code + ". It expires in "
                        + timeToLive.toMinutes() + " minutes. If this wasn't you, do not share the code and change your PIN.");
        if (!sent && logCodeWhenMailFails) {
            // LOCAL DEVELOPMENT ONLY (see application.properties).
            log.warn("Mail could not be sent; withdrawal code for {} is {}", phno, code);
        }
    }

    /**
     * Call before debiting. Returns normally when no code is needed or the code is right for this exact amount (and then
     * uses it up); throws OtpRequiredException when a code is needed and none was sent, InvalidWithdrawalCodeException otherwise.
     */
    @Transactional(noRollbackFor = {InvalidWithdrawalCodeException.class, OtpRequiredException.class})
    public void requireValidCode(long phno, BigDecimal amount, String code) {
        if (!isRequiredFor(amount)) {
            return;
        }
        if (code == null || code.isBlank()) {
            throw new OtpRequiredException("A confirmation code is required to withdraw INR " + threshold.toPlainString()
                    + " or more. Request a code, then send it with this withdrawal.");
        }
        WithdrawalOtp otp = otps.findByPhno(phno).orElseThrow(
                () -> new InvalidWithdrawalCodeException("Invalid or expired code."));
        if (!otp.getExpiresAt().isAfter(clock.instant())) {
            otps.delete(otp);
            throw new InvalidWithdrawalCodeException("Invalid or expired code.");
        }
        if (otp.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            otps.delete(otp);
            throw new InvalidWithdrawalCodeException("Too many incorrect attempts. Request a new code.");
        }
        boolean codeMatches = MessageDigest.isEqual(otp.getOtpHash().getBytes(StandardCharsets.UTF_8),
                hash(code.trim()).getBytes(StandardCharsets.UTF_8));
        boolean amountMatches = otp.getAmount().compareTo(amount) == 0;
        if (!codeMatches || !amountMatches) {
            otp.setAttempts(otp.getAttempts() + 1);
            otps.save(otp);
            throw new InvalidWithdrawalCodeException(codeMatches
                    ? "This code was issued for a different amount. Request a new code for this withdrawal."
                    : "Invalid or expired code.");
        }
        otps.delete(otp);
    }

    @Scheduled(fixedRateString = "${bank.withdraw-otp.cleanup-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        long removed = otps.deleteByExpiresAtBefore(clock.instant());
        if (removed > 0) {
            log.info("Purged {} expired withdrawal code(s)", removed);
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
