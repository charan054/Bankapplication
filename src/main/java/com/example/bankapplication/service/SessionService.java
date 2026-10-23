package com.example.bankapplication.service;

import com.example.bankapplication.entity.BankSession;
import com.example.bankapplication.exception.UnauthorizedException;
import com.example.bankapplication.repository.BankSessionRepository;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Each customer login gets its own random token, stored only as a hash. Mirrors PhonepayService's SessionService,
 * kept as a separate class here since these are two independent applications.
 */
@Service
public class SessionService {
    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

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

    /** What a customer sees in "Active logins". The caller's address is shortened, the browser string reduced to a label. */
    public record SessionView(long id, Instant createdAt, Instant expiresAt, String device, String address, boolean current) {
    }

    /** Records where a just-issued login came from. Best effort: a problem here never fails the login. */
    @Transactional
    public void annotate(String token, String userAgent, String remoteAddr) {
        if (token == null || token.isBlank()) {
            return;
        }
        sessions.findByTokenHash(hash(token)).ifPresent(s -> {
            s.setUserAgent(userAgent == null ? null : (userAgent.length() > 200 ? userAgent.substring(0, 200) : userAgent));
            s.setRemoteAddr(remoteAddr == null ? null : (remoteAddr.length() > 45 ? remoteAddr.substring(0, 45) : remoteAddr));
            sessions.save(s);
        });
    }

    @Transactional(readOnly = true)
    public java.util.List<SessionView> listFor(long phno, String currentToken) {
        String currentHash = currentToken == null || currentToken.isBlank() ? null : hash(currentToken);
        return sessions.findByPhnoAndExpiresAtAfterOrderByCreatedAtDesc(phno, clock.instant()).stream()
                .map(s -> new SessionView(s.getId(), s.getCreatedAt(), s.getExpiresAt(), deviceLabel(s.getUserAgent()),
                        maskAddress(s.getRemoteAddr()), s.getTokenHash().equals(currentHash)))
                .toList();
    }

    /** Ends one of the caller's OWN logins; returns false when there is no such login for them. */
    @Transactional
    public boolean endOwned(long phno, long sessionId) {
        return sessions.findByIdAndPhno(sessionId, phno).map(s -> {
            sessions.delete(s);
            return true;
        }).orElse(false);
    }

    /** Ends every login of the caller, optionally keeping the one making the request. Returns how many were ended. */
    @Transactional
    public int endAll(long phno, String keepToken) {
        String keepHash = keepToken == null || keepToken.isBlank() ? null : hash(keepToken);
        int ended = 0;
        for (BankSession s : sessions.findByPhnoAndExpiresAtAfterOrderByCreatedAtDesc(phno, Instant.EPOCH)) {
            if (!s.getTokenHash().equals(keepHash)) {
                sessions.delete(s);
                ended++;
            }
        }
        return ended;
    }

    static String deviceLabel(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown device";
        }
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Chrome/") ? "Chrome"
                : ua.contains("Firefox/") ? "Firefox" : ua.contains("Safari/") ? "Safari" : ua.contains("okhttp") ? "App" : "Browser";
        String os = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Mac OS") ? "macOS" : ua.contains("Linux") ? "Linux" : null;
        return os == null ? browser : browser + " on " + os;
    }

    static String maskAddress(String addr) {
        if (addr == null || addr.isBlank()) {
            return null;
        }
        int dot = addr.lastIndexOf('.');
        if (dot > 0 && addr.indexOf(':') < 0) {
            return addr.substring(0, dot) + ".x";
        }
        int colons = addr.indexOf(':', addr.indexOf(':') + 1);
        return colons > 0 ? addr.substring(0, colons) + ":..." : addr;
    }

    // Called whenever a phone number stops meaning what it used to (the account behind it was deleted, its
    // number was changed, or its PIN was reset by an admin) - otherwise a token issued under the old meaning
    // stays valid and can end up authenticating against whoever the phone number belongs to next.
    @Transactional
    public void invalidateAllFor(long phno) {
        sessions.deleteByPhno(phno);
    }

    // start()/authenticate() only ever clean up sessions belonging to the phone number they already happen to be
    // touching. A customer who logs in once and never comes back would otherwise leave a session row forever.
    @Scheduled(fixedRateString = "${bank.session.cleanup-interval-minutes:60}", timeUnit = TimeUnit.MINUTES)
    @Transactional
    public void purgeExpiredSessions() {
        long removed = sessions.deleteByExpiresAtBefore(clock.instant());
        if (removed > 0) {
            log.info("Purged {} expired session(s)", removed);
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
