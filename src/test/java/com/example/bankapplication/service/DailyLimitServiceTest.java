package com.example.bankapplication.service;

import com.example.bankapplication.entity.Bank;
import com.example.bankapplication.exception.WithdrawException;
import com.example.bankapplication.repository.BankRepository;
import com.example.bankapplication.repository.BankTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyLimitServiceTest {
    private static final long PHNO = 9876543210L;
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private BankRepository users;
    private BankTransactionRepository transactions;
    private SecurityAlertService alerts;
    private DailyLimitService service;
    private Bank bank;

    @BeforeEach
    void setUp() {
        users = mock(BankRepository.class);
        transactions = mock(BankTransactionRepository.class);
        alerts = mock(SecurityAlertService.class);
        service = new DailyLimitService(users, transactions, Clock.fixed(NOW, ZoneOffset.UTC),
                new BigDecimal("100000"), new BigDecimal("1000000"), 24, "Asia/Kolkata");
        service.setAlerts(alerts);
        bank = new Bank();
        bank.setPhno(PHNO);
        bank.setEmail("a@example.com");
        when(users.findByphno(PHNO)).thenReturn(bank);
        when(transactions.sumDebitsSince(anyLong(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void anAccountWithNoChosenLimitUsesTheBankDefault() {
        assertEquals(0, new BigDecimal("100000").compareTo(service.view(PHNO).dailyLimit()));
        assertTrue(service.view(PHNO).isDefault());
    }

    @Test
    void aDebitWithinTodaysRemainingAllowanceIsFineAndOneOverItIsRefused() {
        when(transactions.sumDebitsSince(anyLong(), any())).thenReturn(new BigDecimal("90000"));

        service.requireWithinLimit(bank, new BigDecimal("10000"));
        WithdrawException e = assertThrows(WithdrawException.class,
                () -> service.requireWithinLimit(bank, new BigDecimal("10000.01")));
        assertTrue(e.getMessage().contains("daily limit of INR 100000"));
        assertTrue(e.getMessage().contains("10000"));
    }

    @Test
    void loweringTheLimitIsImmediateAndDropsAnyWaitingRaise() {
        bank.setPendingDailyLimit(new BigDecimal("500000"));
        bank.setPendingLimitEffectiveAt(NOW.plusSeconds(3600));

        service.change(PHNO, new BigDecimal("20000"));

        assertEquals(0, new BigDecimal("20000.00").compareTo(bank.getDailyLimit()));
        assertNull(bank.getPendingDailyLimit());
        verify(alerts).alert(SecurityAlertService.Kind.LIMIT_CHANGED, bank, "a@example.com", NOW);
    }

    @Test
    void raisingTheLimitWaitsForTheDelayBeforeItApplies() {
        service.change(PHNO, new BigDecimal("500000"));

        assertNull(bank.getDailyLimit());                                         // not in force yet
        assertEquals(0, new BigDecimal("500000.00").compareTo(bank.getPendingDailyLimit()));
        assertEquals(NOW.plus(Duration.ofHours(24)), bank.getPendingLimitEffectiveAt());
        assertEquals(0, new BigDecimal("100000").compareTo(service.view(PHNO).dailyLimit()));
    }

    @Test
    void aRaiseWhoseWaitIsOverIsPromotedWhenTheLimitIsNextRead() {
        bank.setDailyLimit(new BigDecimal("50000"));
        bank.setPendingDailyLimit(new BigDecimal("200000"));
        bank.setPendingLimitEffectiveAt(NOW.minusSeconds(1));

        assertEquals(0, new BigDecimal("200000").compareTo(service.view(PHNO).dailyLimit()));
        assertNull(bank.getPendingDailyLimit());
    }

    @Test
    void theLimitMustBePositiveAndNotAboveTheBankMaximum() {
        assertThrows(WithdrawException.class, () -> service.change(PHNO, BigDecimal.ZERO));
        assertThrows(WithdrawException.class, () -> service.change(PHNO, null));
        assertThrows(WithdrawException.class, () -> service.change(PHNO, new BigDecimal("1000000.01")));
    }
}
