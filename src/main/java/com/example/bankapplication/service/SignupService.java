package com.example.bankapplication.service;

import com.example.bankapplication.dto.SignupRequest;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.PendingSignup;
import com.example.bankapplication.exception.InvalidOtpException;
import com.example.bankapplication.repository.PendingSignupRepository;
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
 * Self-signup confirmed by an emailed 6-digit code. Step 1 (requestSignup) checks the details exactly like a direct
 * registration, parks them with a hashed code and emails the code; step 2 (verify) creates the account only if the code
 * matches. Mirrors PinResetService: hashed code, expiry, a cap on wrong guesses, scheduled cleanup. Accounts created from
 * the admin console (POST /bank/save with the admin key) do not go through this.
 */
@Service
public class SignupService {
    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    static final int MAX_VERIFY_ATTEMPTS = 5;

    private final PendingSignupRepository pending;
    private final BankService bankService;
    private final MailService mailService;
    private final Clock clock;
    private final Duration timeToLive;
    private final boolean logCodeWhenMailFails;
    private final SecureRandom random = new SecureRandom();

    public SignupService(PendingSignupRepository pending, BankService bankService, MailService mailService, Clock clock,
                         @Value("${bank.signup.ttl-minutes:10}") long ttlMinutes,
                         @Value("${bank.signup.log-codes-when-mail-fails:false}") boolean logCodeWhenMailFails) {
        this.pending = pending;
        this.bankService = bankService;
        this.mailService = mailService;
        this.clock = clock;
        this.timeToLive = Duration.ofMinutes(ttlMinutes);
        this.logCodeWhenMailFails = logCodeWhenMailFails;
    }

    @Transactional
    public void requestSignup(SignupRequest request) {
        // Same checks as a direct registration, up front, so nobody is emailed a code for a signup that cannot succeed.
        bankService.validateNewAccount(request.phno(), request.aadharNumber());

        Instant now = clock.instant();
        pending.deleteByPhno(request.phno());
        // delete + insert for the same unique phone number must not collide inside one transaction
        pending.flush();

        String code = String.format("%06d", random.nextInt(1_000_000));
        PendingSignup signup = new PendingSignup();
        signup.setPhno(request.phno());
        signup.setOtpHash(hash(code));
        signup.setFirstName(request.firstName());
        signup.setLastName(request.lastName());
        signup.setAadharNumber(request.aadharNumber());
        signup.setEmail(request.email());
        signup.setPinHash(bankService.encodePin(request.pin()));
        signup.setCreatedAt(now);
        signup.setExpiresAt(now.plus(timeToLive));
        signup.setAttempts(0);
        pending.save(signup);

        boolean sent = mailService.send(request.email(), "Confirm your new account",
                "Your account confirmation code is " + code + ". It expires in " + timeToLive.toMinutes()
                        + " minutes. If you didn't try to open an account, you can ignore this email.");
        if (!sent && logCodeWhenMailFails) {
            // LOCAL DEVELOPMENT ONLY (see application.properties): lets you finish a signup without mail credentials.
            log.warn("Mail could not be sent; signup code for {} is {}", request.phno(), code);
        }
    }

    @Transactional(noRollbackFor = InvalidOtpException.class)
    public Bank verify(long phno, String code) {
        PendingSignup signup = pending.findByPhno(phno).orElseThrow(
                () -> new InvalidOtpException("Invalid or expired code."));

        if (!signup.getExpiresAt().isAfter(clock.instant())) {
            pending.delete(signup);
            throw new InvalidOtpException("Invalid or expired code.");
        }
        if (signup.getAttempts() >= MAX_VERIFY_ATTEMPTS) {
            pending.delete(signup);
            throw new InvalidOtpException("Too many incorrect attempts. Start the signup again.");
        }
        if (!MessageDigest.isEqual(signup.getOtpHash().getBytes(StandardCharsets.UTF_8), hash(code).getBytes(StandardCharsets.UTF_8))) {
            signup.setAttempts(signup.getAttempts() + 1);
            pending.save(signup);
            throw new InvalidOtpException("Invalid or expired code.");
        }

        pending.delete(signup);
        return bankService.createAccount(signup.getFirstName(), signup.getLastName(), signup.getAadharNumber(),
                signup.getPhno(), signup.getEmail(), signup.getPinHash());
    }

    // A signup nobody confirms would otherwise sit in the table (with its details) forever.
    @Scheduled(fixedRateString = "${bank.signup.cleanup-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpired() {
        long removed = pending.deleteByExpiresAtBefore(clock.instant());
        if (removed > 0) {
            log.info("Purged {} expired pending signup(s)", removed);
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
