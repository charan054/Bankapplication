package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.PinResetOtp;
import com.example.bankapplication.exception.InvalidOtpException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.PinResetOtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PinResetServiceTest {

    private static final long PHNO = 9876543210L;
    private static final String EMAIL = "customer@example.com";
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private static class MovableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock
    private PinResetOtpRepository otps;
    @Mock
    private BankRepository users;
    @Mock
    private BankService bankService;
    @Mock
    private MailService mailService;
    private MovableClock clock;
    private PinResetService service;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        service = new PinResetService(otps, users, bankService, mailService, clock, 10);
    }

    private Bank bankWithEmail(String email) {
        Bank bank = new Bank();
        bank.setPhno(PHNO);
        bank.setEmail(email);
        return bank;
    }

    private PinResetOtp savedOtp() {
        ArgumentCaptor<PinResetOtp> captor = ArgumentCaptor.forClass(PinResetOtp.class);
        verify(otps).save(captor.capture());
        return captor.getValue();
    }

    // ---------- requestReset ----------

    @Test
    void requestReset_unknownPhoneNumber_sendsNoEmailAndStoresNoCode() {
        when(users.findByphno(PHNO)).thenReturn(null);

        service.requestReset(PHNO);

        verify(mailService, never()).send(anyString(), anyString(), anyString());
        verify(otps, never()).save(any());
    }

    @Test
    void requestReset_accountWithNoEmailOnFile_sendsNoEmail() {
        when(users.findByphno(PHNO)).thenReturn(bankWithEmail(null));

        service.requestReset(PHNO);

        verify(mailService, never()).send(anyString(), anyString(), anyString());
        verify(otps, never()).save(any());
    }

    @Test
    void requestReset_realAccount_emailsASixDigitCodeAndStoresOnlyItsHash() {
        when(users.findByphno(PHNO)).thenReturn(bankWithEmail(EMAIL));

        service.requestReset(PHNO);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EMAIL), anyString(), bodyCaptor.capture());
        Pattern sixDigits = Pattern.compile("\\b\\d{6}\\b");
        java.util.regex.Matcher matcher = sixDigits.matcher(bodyCaptor.getValue());
        assertTrue(matcher.find(), "email body should contain a 6-digit code: " + bodyCaptor.getValue());
        String code = matcher.group();

        PinResetOtp stored = savedOtp();
        assertEquals(PHNO, stored.getPhno());
        assertEquals(64, stored.getOtpHash().length());   // SHA-256 as hex
        assertTrue(!stored.getOtpHash().contains(code), "the raw code must never be stored");
        assertEquals(NOW.plus(Duration.ofMinutes(10)), stored.getExpiresAt());
        assertEquals(0, stored.getAttempts());
    }

    @Test
    void requestReset_replacesAnyCodeAlreadyOutstandingForThatNumber() {
        when(users.findByphno(PHNO)).thenReturn(bankWithEmail(EMAIL));

        service.requestReset(PHNO);

        verify(otps).deleteByPhno(PHNO);
    }

    // ---------- resetPin ----------

    @Test
    void resetPin_correctCode_setsThePinAndDeletesTheCode() {
        PinResetOtp stored = new PinResetOtp();
        stored.setPhno(PHNO);
        stored.setOtpHash(sha256("123456"));
        stored.setExpiresAt(NOW.plus(Duration.ofMinutes(5)));
        stored.setAttempts(0);
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(stored));

        service.resetPin(PHNO, "123456", "4321");

        verify(bankService).setPin(PHNO, "4321");
        verify(otps).delete(stored);
    }

    @Test
    void resetPin_wrongCode_isRejectedAndCountsAsAnAttempt() {
        PinResetOtp stored = new PinResetOtp();
        stored.setPhno(PHNO);
        stored.setOtpHash(sha256("123456"));
        stored.setExpiresAt(NOW.plus(Duration.ofMinutes(5)));
        stored.setAttempts(0);
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(stored));

        assertThrows(InvalidOtpException.class, () -> service.resetPin(PHNO, "000000", "4321"));

        verify(bankService, never()).setPin(anyLong(), anyString());
        assertEquals(1, stored.getAttempts());
        verify(otps).save(stored);
    }

    @Test
    void resetPin_noOutstandingCode_isRejected() {
        when(otps.findByPhno(PHNO)).thenReturn(Optional.empty());

        InvalidOtpException ex = assertThrows(InvalidOtpException.class,
                () -> service.resetPin(PHNO, "123456", "4321"));

        assertEquals("Invalid or expired code.", ex.getMessage());
    }

    @Test
    void resetPin_expiredCode_isRejectedAndRemoved() {
        PinResetOtp stored = new PinResetOtp();
        stored.setPhno(PHNO);
        stored.setOtpHash(sha256("123456"));
        stored.setExpiresAt(NOW.minusSeconds(1));
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(stored));

        assertThrows(InvalidOtpException.class, () -> service.resetPin(PHNO, "123456", "4321"));

        verify(otps).delete(stored);
        verify(bankService, never()).setPin(anyLong(), anyString());
    }

    @Test
    void resetPin_tooManyWrongAttempts_isRejectedEvenWithTheRightCode() {
        PinResetOtp stored = new PinResetOtp();
        stored.setPhno(PHNO);
        stored.setOtpHash(sha256("123456"));
        stored.setExpiresAt(NOW.plus(Duration.ofMinutes(5)));
        stored.setAttempts(PinResetService.MAX_VERIFY_ATTEMPTS);
        when(otps.findByPhno(PHNO)).thenReturn(Optional.of(stored));

        assertThrows(InvalidOtpException.class, () -> service.resetPin(PHNO, "123456", "4321"));

        verify(otps).delete(stored);
        verify(bankService, never()).setPin(anyLong(), anyString());
    }

    // ---------- purgeExpiredCodes (scheduled housekeeping) ----------

    @Test
    void purgeExpiredCodes_deletesEverythingExpiredAsOfNow() {
        service.purgeExpiredCodes();

        verify(otps).deleteByExpiresAtBefore(NOW);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
