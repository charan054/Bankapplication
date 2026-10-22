package com.example.bankapplication.service;

import com.example.bankapplication.dto.SignupRequest;
import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.entity.PendingSignup;
import com.example.bankapplication.exception.InvalidOtpException;
import com.example.bankapplication.exception.MobileNumberException;
import com.example.bankapplication.repository.PendingSignupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    private static final long PHNO = 9876543210L;
    private static final long AADHAR = 123456789012L;
    private static final String EMAIL = "new.customer@example.com";
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private static class MovableClock extends Clock {
        Instant now = NOW;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Mock
    private PendingSignupRepository pending;
    @Mock
    private BankService bankService;
    @Mock
    private MailService mailService;
    private MovableClock clock;
    private SignupService service;

    private final SignupRequest request = new SignupRequest("Charan", "Kumar", AADHAR, PHNO, "1234", EMAIL);

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        service = new SignupService(pending, bankService, mailService, clock, 10, false);
    }

    private static String sha256(String code) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
    }

    private PendingSignup stored(String code, int attempts, Instant expiresAt) throws Exception {
        PendingSignup s = new PendingSignup();
        s.setPhno(PHNO);
        s.setOtpHash(sha256(code));
        s.setFirstName("Charan");
        s.setLastName("Kumar");
        s.setAadharNumber(AADHAR);
        s.setEmail(EMAIL);
        s.setPinHash("hashed-pin");
        s.setExpiresAt(expiresAt);
        s.setAttempts(attempts);
        return s;
    }

    // ---------- requestSignup ----------

    @Test
    void requestSignup_checksTheDetailsFirstAndSendsNoEmailWhenTheyAreRejected() {
        doThrow(new MobileNumberException("Invalid mobile number")).when(bankService).validateNewAccount(PHNO, AADHAR);

        assertThrows(MobileNumberException.class, () -> service.requestSignup(request));

        verify(mailService, never()).send(anyString(), anyString(), anyString());
        verify(pending, never()).save(any());
    }

    @Test
    void requestSignup_emailsASixDigitCodeAndStoresOnlyItsHashAndTheHashedPin() {
        when(bankService.encodePin("1234")).thenReturn("hashed-pin");

        service.requestSignup(request);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EMAIL), anyString(), body.capture());
        Matcher m = Pattern.compile("\\b\\d{6}\\b").matcher(body.getValue());
        assertTrue(m.find(), "email body should contain a 6-digit code: " + body.getValue());
        String code = m.group();

        ArgumentCaptor<PendingSignup> saved = ArgumentCaptor.forClass(PendingSignup.class);
        verify(pending).save(saved.capture());
        PendingSignup s = saved.getValue();
        assertEquals(PHNO, s.getPhno());
        assertEquals(64, s.getOtpHash().length());
        assertTrue(!s.getOtpHash().contains(code), "the raw code must never be stored");
        assertEquals("hashed-pin", s.getPinHash());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), s.getExpiresAt());
        assertEquals(0, s.getAttempts());
        verify(bankService, never()).createAccount(anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void requestSignup_replacesAnySignupAlreadyPendingForThatNumber() {
        service.requestSignup(request);

        verify(pending).deleteByPhno(PHNO);
    }

    // ---------- verify ----------

    @Test
    void verify_correctCode_createsTheAccountFromTheStoredDetailsAndDeletesThePendingSignup() throws Exception {
        PendingSignup s = stored("123456", 0, NOW.plus(Duration.ofMinutes(5)));
        when(pending.findByPhno(PHNO)).thenReturn(Optional.of(s));
        Bank created = new Bank();
        when(bankService.createAccount("Charan", "Kumar", AADHAR, PHNO, EMAIL, "hashed-pin")).thenReturn(created);

        Bank result = service.verify(PHNO, "123456");

        assertSame(created, result);
        verify(pending).delete(s);
    }

    @Test
    void verify_wrongCode_countsTheAttemptAndCreatesNothing() throws Exception {
        PendingSignup s = stored("123456", 1, NOW.plus(Duration.ofMinutes(5)));
        when(pending.findByPhno(PHNO)).thenReturn(Optional.of(s));

        assertThrows(InvalidOtpException.class, () -> service.verify(PHNO, "000000"));

        assertEquals(2, s.getAttempts());
        verify(pending).save(s);
        verify(bankService, never()).createAccount(anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void verify_afterTooManyWrongAttempts_discardsTheSignupEvenForTheRightCode() throws Exception {
        PendingSignup s = stored("123456", SignupService.MAX_VERIFY_ATTEMPTS, NOW.plus(Duration.ofMinutes(5)));
        when(pending.findByPhno(PHNO)).thenReturn(Optional.of(s));

        assertThrows(InvalidOtpException.class, () -> service.verify(PHNO, "123456"));

        verify(pending).delete(s);
        verify(bankService, never()).createAccount(anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void verify_expiredCode_isRejectedAndRemoved() throws Exception {
        PendingSignup s = stored("123456", 0, NOW.minusSeconds(1));
        when(pending.findByPhno(PHNO)).thenReturn(Optional.of(s));

        assertThrows(InvalidOtpException.class, () -> service.verify(PHNO, "123456"));

        verify(pending).delete(s);
    }

    @Test
    void verify_nothingPendingForThatNumber_isRejected() {
        when(pending.findByPhno(PHNO)).thenReturn(Optional.empty());

        assertThrows(InvalidOtpException.class, () -> service.verify(PHNO, "123456"));
    }
}
