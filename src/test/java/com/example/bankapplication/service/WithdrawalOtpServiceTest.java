package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.WithdrawalOtp;
import com.example.bankapplication.exception.InvalidWithdrawalCodeException;
import com.example.bankapplication.exception.OtpRequiredException;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.WithdrawalOtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawalOtpServiceTest {

    private static final long PHNO = 9876543210L;
    private static final String EMAIL = "customer@example.com";
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final BigDecimal BIG = new BigDecimal("15000.00");

    private static class MovableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock
    private WithdrawalOtpRepository otps;
    @Mock
    private BankRepository users;
    @Mock
    private MailService mailService;
    private WithdrawalOtpService service;

    @BeforeEach
    void setUp() {
        service = new WithdrawalOtpService(otps, users, mailService, new MovableClock(), new BigDecimal("10000"), 5, false);
    }

    private static String sha256(String code) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
    }

    private WithdrawalOtp stored(String code, BigDecimal amount, int attempts, Instant expiresAt) throws Exception {
        WithdrawalOtp o = new WithdrawalOtp();
        o.setPhno(PHNO);
        o.setOtpHash(sha256(code));
        o.setAmount(amount);
        o.setAttempts(attempts);
        o.setExpiresAt(expiresAt);
        return o;
    }

    private Bank accountWithEmail(String email) {
        Bank b = new Bank();
        b.setPhno(PHNO);
        b.setEmail(email);
        return b;
    }

    // ---------- isRequiredFor ----------

    @Test
    void aCodeIsRequiredFromTheThresholdUpOnly() {
        assertFalse(service.isRequiredFor(new BigDecimal("9999.99")));
        assertTrue(service.isRequiredFor(new BigDecimal("10000")));
        assertTrue(service.isRequiredFor(BIG));
    }

    @Test
    void aThresholdOfZeroTurnsTheFeatureOff() {
        WithdrawalOtpService off = new WithdrawalOtpService(otps, users, mailService, new MovableClock(), BigDecimal.ZERO, 5, false);

        assertFalse(off.isRequiredFor(new BigDecimal("1000000")));
        off.requireValidCode(PHNO, new BigDecimal("1000000"), null);
    }

    // ---------- request ----------

    @Test
    void request_emailsASixDigitCodeBoundToTheAmountAndStoresOnlyItsHash() {
        when(users.findByphno(PHNO)).thenReturn(accountWithEmail(EMAIL));

        service.request(PHNO, BIG);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EMAIL), anyString(), body.capture());
        Matcher m = Pattern.compile("\\b[0-9]{6}\\b").matcher(body.getValue());
        assertTrue(m.find(), body.getValue());
        assertTrue(body.getValue().contains("15000.00"), "the email should say which amount it confirms");

        ArgumentCaptor<WithdrawalOtp> saved = ArgumentCaptor.forClass(WithdrawalOtp.class);
        verify(otps).save(saved.capture());
        assertEquals(0, BIG.compareTo(saved.getValue().getAmount()));
        assertEquals(64, saved.getValue().getOtpHash().length());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), saved.getValue().getExpiresAt());
        verify(otps).deleteByPhno(PHNO);
    }

    @Test
    void request_belowTheThreshold_isRefusedWithoutEmailing() {
        assertThrows(WithdrawException.class, () -> service.request(PHNO, new BigDecimal("500")));

        verify(mailService, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void request_accountWithoutAnEmail_isToldToAddOne() {
        when(users.findByphno(PHNO)).thenReturn(accountWithEmail(null));

        WithdrawException e = assertThrows(WithdrawException.class, () -> service.request(PHNO, BIG));

        assertTrue(e.getMessage().contains("email"));
        verify(otps, never()).save(any());
    }

    // ---------- requireValidCode ----------

    @Test
    void requireValidCode_smallAmount_needsNothing() {
        service.requireValidCode(PHNO, new BigDecimal("500"), null);

        verify(otps, never()).findByPhno(PHNO);
    }

    @Test
    void requireValidCode_bigAmountWithoutACode_isOtpRequired() {
        assertThrows(OtpRequiredException.class, () -> service.requireValidCode(PHNO, BIG, null));
        assertThrows(OtpRequiredException.class, () -> service.requireValidCode(PHNO, BIG, " "));
    }

    @Test
    void requireValidCode_rightCodeForTheSameAmount_passesAndIsUsedUp() throws Exception {
        WithdrawalOtp o = stored("123456", BIG, 0, NOW.plus(Duration.ofMinutes(3)));
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(o));

        service.requireValidCode(PHNO, new BigDecimal("15000"), "123456");

        verify(otps).delete(o);
    }

    @Test
    void requireValidCode_rightCodeForADifferentAmount_isRejectedAndCounted() throws Exception {
        WithdrawalOtp o = stored("123456", BIG, 0, NOW.plus(Duration.ofMinutes(3)));
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(o));

        InvalidWithdrawalCodeException e = assertThrows(InvalidWithdrawalCodeException.class,
                () -> service.requireValidCode(PHNO, new BigDecimal("90000"), "123456"));

        assertTrue(e.getMessage().contains("different amount"));
        assertEquals(1, o.getAttempts());
        verify(otps, never()).delete(any(WithdrawalOtp.class));
    }

    @Test
    void requireValidCode_wrongCode_isCountedAndKeepsTheCode() throws Exception {
        WithdrawalOtp o = stored("123456", BIG, 0, NOW.plus(Duration.ofMinutes(3)));
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(o));

        assertThrows(InvalidWithdrawalCodeException.class, () -> service.requireValidCode(PHNO, BIG, "000000"));

        assertEquals(1, o.getAttempts());
        verify(otps).save(o);
    }

    @Test
    void requireValidCode_afterTooManyWrongTries_theCodeIsDiscarded() throws Exception {
        WithdrawalOtp o = stored("123456", BIG, WithdrawalOtpService.MAX_VERIFY_ATTEMPTS, NOW.plus(Duration.ofMinutes(3)));
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(o));

        assertThrows(InvalidWithdrawalCodeException.class, () -> service.requireValidCode(PHNO, BIG, "123456"));

        verify(otps).delete(o);
    }

    @Test
    void requireValidCode_expiredCode_isRejected() throws Exception {
        WithdrawalOtp o = stored("123456", BIG, 0, NOW.minusSeconds(1));
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(o));

        assertThrows(InvalidWithdrawalCodeException.class, () -> service.requireValidCode(PHNO, BIG, "123456"));

        verify(otps).delete(o);
    }

    @Test
    void requireValidCode_noCodeWasEverRequested_isRejected() {
        when(otps.findByPhno(PHNO)).thenReturn(Optional.empty());

        assertThrows(InvalidWithdrawalCodeException.class, () -> service.requireValidCode(PHNO, BIG, "123456"));
    }
}
