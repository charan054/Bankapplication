package com.example.bankapplication.service;

import com.example.bankapplication.entity.BankSession;
import com.example.bankapplication.exception.UnauthorizedException;
import com.example.bankapplication.repository.BankSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Each customer login gets its own random token, stored only as a hash. Mirrors PhonepayService's SessionService,
 * kept as a separate class here since these are two independent applications.
 */
@Service
public class SessionService {

    public record IssuedSession(String token, Instant expiresAt) {
    }

    private final BankSessionRepository sessions;
    private final Clock clock;
    private final Duration timeToLive;
    private final SecureRandom random = new SecureRandom();

    public SessionService(BankSessionRepository sessions, Clock clock,
                          @Value("${bank.session.ttl-minutes:30}") long ttlMinutes) {
        this.sessions = sessions;
        this.clock = clock;
        this.timeToLive = Duration.ofMinutes(ttlMinutes);
    }

    @Transactional
    public IssuedSession start(long phno) {
        Instant now = clock.instant();
        sessions.deleteByPhnoAndExpiresAtBefore(phno, now);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        BankSession session = new BankSession();
        session.setTokenHash(hash(token));
        session.setPhno(phno);
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(timeToLive));
        sessions.save(session);
        return new IssuedSession(token, session.getExpiresAt());
    }

    /** Returns the phone number the token belongs to, or throws if there is no valid login behind it. */
    @Transactional
    public long authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Login required");
        }
        BankSession session = sessions.findByTokenHash(hash(token))
                .orElseThrow(() -> new UnauthorizedException("Invalid session. Please login again."));
        if (!session.getExpiresAt().isAfter(clock.instant())) {
            sessions.delete(session);
            throw new UnauthorizedException("Session expired. Please login again.");
        }
        return session.getPhno();
    }

    @Transactional
    public void end(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteByTokenHash(hash(token));
        }
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
